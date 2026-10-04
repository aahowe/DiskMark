// Native storage benchmark engine.
//
// Uses O_DIRECT + page-aligned buffers so reads/writes hit the flash device
// instead of the page cache, similar to CrystalDiskMark's FILE_FLAG_NO_BUFFERING.
// Queue depth is emulated with QD x T worker threads issuing synchronous
// pread/pwrite calls (Android has no portable async I/O API for apps).

#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "DiskBench"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define ALIGN 4096
#define MAX_WORKERS 64

static atomic_int g_cancel = 0;

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static uint64_t xorshift64(uint64_t *s) {
    uint64_t x = *s;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    return *s = x;
}

static void fill_random(uint8_t *buf, size_t len, uint64_t seed) {
    uint64_t s = seed | 1;
    for (size_t i = 0; i + 8 <= len; i += 8) {
        uint64_t v = xorshift64(&s);
        memcpy(buf + i, &v, 8);
    }
}

// Opens the file with O_DIRECT when possible; reports whether it succeeded.
static int open_test_file(const char *path, int flags, int *direct) {
    int fd = open(path, flags | O_DIRECT | O_CLOEXEC, 0600);
    if (fd >= 0) {
        *direct = 1;
        return fd;
    }
    *direct = 0;
    fd = open(path, flags | O_CLOEXEC, 0600);
    if (fd >= 0) posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
    return fd;
}

typedef struct {
    int fd;
    int write;
    int random;
    int64_t block;
    int64_t file_size;
    int64_t start_offset;   // sequential workers each own a slice of the file
    int64_t slice_size;
    int64_t deadline_ns;
    uint64_t seed;
    int64_t bytes;
    int64_t ops;
    int error;
} worker_t;

static void *worker_main(void *arg) {
    worker_t *w = (worker_t *) arg;
    void *buf = NULL;
    if (posix_memalign(&buf, ALIGN, (size_t) w->block) != 0) {
        w->error = ENOMEM;
        return NULL;
    }
    fill_random(buf, (size_t) w->block, w->seed);

    uint64_t rng = w->seed | 1;
    int64_t blocks = w->file_size / w->block;
    int64_t slice_blocks = w->slice_size / w->block;
    if (slice_blocks < 1) slice_blocks = 1;
    int64_t seq = 0;

    while (!atomic_load_explicit(&g_cancel, memory_order_relaxed)) {
        // Check the clock every 16 ops to keep overhead low for 4K I/O.
        if ((w->ops & 15) == 0 && now_ns() >= w->deadline_ns) break;

        int64_t off;
        if (w->random) {
            off = (int64_t) (xorshift64(&rng) % (uint64_t) blocks) * w->block;
        } else {
            off = w->start_offset + (seq % slice_blocks) * w->block;
            seq++;
        }
        ssize_t n = w->write ? pwrite(w->fd, buf, (size_t) w->block, off)
                             : pread(w->fd, buf, (size_t) w->block, off);
        if (n < 0) {
            if (errno == EINTR) continue;
            w->error = errno;
            break;
        }
        w->bytes += n;
        w->ops++;
    }
    free(buf);
    return NULL;
}

JNIEXPORT void JNICALL
Java_com_howe_diskmark_bench_NativeBench_cancel(JNIEnv *env, jclass clazz) {
    atomic_store(&g_cancel, 1);
}

JNIEXPORT void JNICALL
Java_com_howe_diskmark_bench_NativeBench_resetCancel(JNIEnv *env, jclass clazz) {
    atomic_store(&g_cancel, 0);
}

// Creates (or recreates) the test file filled with incompressible data.
// Returns 0 on success, otherwise an errno value.
JNIEXPORT jint JNICALL
Java_com_howe_diskmark_bench_NativeBench_prepareFile(JNIEnv *env, jclass clazz,
                                                         jstring jpath, jlong size) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int direct;
    int fd = open_test_file(path, O_RDWR | O_CREAT | O_TRUNC, &direct);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (fd < 0) return errno;

    const size_t chunk = 4 * 1024 * 1024;
    void *buf = NULL;
    if (posix_memalign(&buf, ALIGN, chunk) != 0) {
        close(fd);
        return ENOMEM;
    }
    int err = 0;
    for (int64_t off = 0; off < size && !atomic_load(&g_cancel); off += chunk) {
        fill_random(buf, chunk, (uint64_t) off + 0x9E3779B97F4A7C15ULL);
        size_t len = (size_t) ((size - off) < (int64_t) chunk ? (size - off) : (int64_t) chunk);
        if (pwrite(fd, buf, len, off) != (ssize_t) len) {
            err = errno ? errno : EIO;
            break;
        }
    }
    if (!err && fsync(fd) != 0) err = errno;
    free(buf);
    close(fd);
    return err;
}

// Runs one benchmark pass. Result array layout:
// [0] bytes transferred, [1] operations, [2] elapsed ns, [3] O_DIRECT used (1/0), [4] errno
JNIEXPORT jlongArray JNICALL
Java_com_howe_diskmark_bench_NativeBench_run(JNIEnv *env, jclass clazz, jstring jpath,
                                                 jboolean write, jboolean random, jint block_size,
                                                 jint queue_depth, jint threads, jlong file_size,
                                                 jlong duration_ms) {
    jlong out[5] = {0, 0, 0, 0, 0};
    jlongArray result = (*env)->NewLongArray(env, 5);

    int workers = queue_depth * threads;
    if (workers < 1) workers = 1;
    if (workers > MAX_WORKERS) workers = MAX_WORKERS;

    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int direct;
    int fd = open_test_file(path, write ? O_RDWR : O_RDONLY, &direct);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (fd < 0) {
        out[4] = errno;
        (*env)->SetLongArrayRegion(env, result, 0, 5, out);
        return result;
    }

    worker_t ws[MAX_WORKERS];
    pthread_t tids[MAX_WORKERS];
    int64_t slice = (file_size / workers) / block_size * block_size;
    int64_t start = now_ns();
    int64_t deadline = start + duration_ms * 1000000LL;

    for (int i = 0; i < workers; i++) {
        ws[i] = (worker_t) {
            .fd = fd, .write = write, .random = random, .block = block_size,
            .file_size = file_size, .start_offset = slice * i, .slice_size = slice,
            .deadline_ns = deadline, .seed = (uint64_t) start ^ ((uint64_t) (i + 1) * 0x2545F4914F6CDD1DULL),
        };
        pthread_create(&tids[i], NULL, worker_main, &ws[i]);
    }
    for (int i = 0; i < workers; i++) pthread_join(tids[i], NULL);
    // Include flush time so buffered (non-direct) writes are not overstated.
    if (write) fsync(fd);
    int64_t elapsed = now_ns() - start;
    close(fd);

    for (int i = 0; i < workers; i++) {
        out[0] += ws[i].bytes;
        out[1] += ws[i].ops;
        if (ws[i].error && !out[4]) out[4] = ws[i].error;
    }
    out[2] = elapsed;
    out[3] = direct;
    if (out[4]) LOGE("benchmark error: %s", strerror((int) out[4]));
    (*env)->SetLongArrayRegion(env, result, 0, 5, out);
    return result;
}
