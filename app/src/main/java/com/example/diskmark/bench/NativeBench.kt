package com.example.diskmark.bench

/** JNI bridge to the native O_DIRECT benchmark engine (src/main/cpp/diskbench.c). */
object NativeBench {
  init {
    System.loadLibrary("diskbench")
  }

  @JvmStatic external fun prepareFile(path: String, size: Long): Int

  /** Returns [bytes, ops, elapsedNs, directIo, errno]. */
  @JvmStatic
  external fun run(
    path: String,
    write: Boolean,
    random: Boolean,
    blockSize: Int,
    queueDepth: Int,
    threads: Int,
    fileSize: Long,
    durationMs: Long,
  ): LongArray

  @JvmStatic external fun cancel()

  @JvmStatic external fun resetCancel()
}
