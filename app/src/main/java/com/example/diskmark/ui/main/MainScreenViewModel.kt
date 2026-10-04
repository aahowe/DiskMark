package com.example.diskmark.ui.main

import android.app.Application
import android.os.StatFs
import android.system.Os
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.diskmark.bench.BenchTest
import com.example.diskmark.bench.BenchmarkException
import com.example.diskmark.bench.Measurement
import com.example.diskmark.bench.NativeBench
import com.example.diskmark.bench.ResultUnit
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TestResult(val read: Measurement? = null, val write: Measurement? = null)

sealed interface RunStatus {
  data object Idle : RunStatus

  data class Running(val message: String) : RunStatus

  data object Done : RunStatus

  data object Cancelled : RunStatus

  data class Failed(val message: String) : RunStatus
}

data class MainUiState(
  val tests: List<BenchTest> = BenchTest.DEFAULT,
  val results: Map<String, TestResult> = emptyMap(),
  val count: Int = 3,
  val sizeMb: Int = 256,
  val durationSec: Int = 3,
  val unit: ResultUnit = ResultUnit.MBS,
  val status: RunStatus = RunStatus.Idle,
  val directIo: Boolean? = null,
  val storagePath: String = "",
  val freeBytes: Long = 0,
  val totalBytes: Long = 0,
) {
  val running: Boolean
    get() = status is RunStatus.Running
}

private const val TAG = "DiskMark"

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {
  private val testDir: File = application.filesDir
  private val testFile = File(testDir, "diskmark.tmp")
  private val _uiState = MutableStateFlow(MainUiState(storagePath = testDir.absolutePath))
  val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
  private var job: Job? = null

  init {
    refreshStorage()
  }

  fun setCount(count: Int) = _uiState.update { it.copy(count = count) }

  fun setSizeMb(sizeMb: Int) = _uiState.update { it.copy(sizeMb = sizeMb) }

  fun setDuration(seconds: Int) = _uiState.update { it.copy(durationSec = seconds) }

  fun setUnit(unit: ResultUnit) = _uiState.update { it.copy(unit = unit) }

  fun runAll() = start(_uiState.value.tests)

  fun runSingle(test: BenchTest) = start(listOf(test))

  fun cancel() {
    NativeBench.cancel()
    job?.cancel()
  }

  private fun start(tests: List<BenchTest>) {
    if (_uiState.value.running) return
    val config = _uiState.value
    _uiState.update { state -> state.copy(results = state.results - tests.map { it.id }.toSet()) }
    job =
      viewModelScope.launch {
        try {
          withContext(Dispatchers.IO) { execute(tests, config) }
          _uiState.update { it.copy(status = RunStatus.Done) }
          logResults(tests)
        } catch (e: BenchmarkException) {
          _uiState.update { it.copy(status = RunStatus.Failed(e.message ?: "I/O error")) }
        } catch (e: kotlinx.coroutines.CancellationException) {
          _uiState.update { it.copy(status = RunStatus.Cancelled) }
          throw e
        } finally {
          testFile.delete()
          refreshStorage()
        }
      }
  }

  private suspend fun execute(tests: List<BenchTest>, config: MainUiState) {
    NativeBench.resetCancel()
    val fileSize = config.sizeMb.toLong() shl 20
    if (testDir.usableSpace < fileSize + (64L shl 20)) {
      throw BenchmarkException(28, "Not enough free space for a ${config.sizeMb} MiB test file")
    }
    setStatus("Preparing ${config.sizeMb} MiB test file…")
    val err = NativeBench.prepareFile(testFile.absolutePath, fileSize)
    if (err != 0) throw BenchmarkException(err, "Failed to create test file: ${Os.strerror(err)}")

    for (test in tests) {
      for (write in listOf(false, true)) {
        val direction = if (write) "Write" else "Read"
        repeat(config.count) { pass ->
          setStatus("${test.label} ${test.queueLabel} $direction ${pass + 1}/${config.count}")
          val m = measure(test, write, fileSize, config.durationSec * 1000L)
          _uiState.update { state ->
            val prev = state.results[test.id] ?: TestResult()
            // Like CrystalDiskMark, keep the best of all passes.
            val next =
              if (write) prev.copy(write = best(prev.write, m)) else prev.copy(read = best(prev.read, m))
            state.copy(results = state.results + (test.id to next), directIo = m.directIo)
          }
          delay(500) // Short interval between passes, lets the device settle.
        }
      }
    }
  }

  private fun measure(test: BenchTest, write: Boolean, fileSize: Long, durationMs: Long): Measurement {
    val r =
      NativeBench.run(
        testFile.absolutePath,
        write,
        test.random,
        test.blockSize,
        test.queueDepth,
        test.threads,
        fileSize,
        durationMs,
      )
    if (r[4] != 0L) throw BenchmarkException(r[4].toInt(), "I/O error: ${Os.strerror(r[4].toInt())}")
    return Measurement(bytes = r[0], ops = r[1], elapsedNs = r[2], directIo = r[3] == 1L)
  }

  /** Machine-readable summary for scripts: `adb logcat -s DiskMark`. */
  private fun logResults(tests: List<BenchTest>) {
    val state = _uiState.value
    Log.i(TAG, "CONFIG size=${state.sizeMb}MiB count=${state.count} duration=${state.durationSec}s directIo=${state.directIo}")
    for (test in tests) {
      val r = state.results[test.id] ?: continue
      Log.i(
        TAG,
        String.format(
          Locale.US,
          "RESULT %-6s %-6s read=%.2fMB/s %.0fIOPS write=%.2fMB/s %.0fIOPS",
          test.label,
          test.queueLabel,
          r.read?.mbPerSec ?: 0.0,
          r.read?.iops ?: 0.0,
          r.write?.mbPerSec ?: 0.0,
          r.write?.iops ?: 0.0,
        ),
      )
    }
  }

  private fun best(a: Measurement?, b: Measurement) = if (a == null || b.mbPerSec > a.mbPerSec) b else a

  private fun setStatus(message: String) = _uiState.update { it.copy(status = RunStatus.Running(message)) }

  private fun refreshStorage() {
    val stat = StatFs(testDir.absolutePath)
    _uiState.update { it.copy(freeBytes = stat.availableBytes, totalBytes = stat.totalBytes) }
  }

  override fun onCleared() {
    NativeBench.cancel()
    testFile.delete()
  }
}
