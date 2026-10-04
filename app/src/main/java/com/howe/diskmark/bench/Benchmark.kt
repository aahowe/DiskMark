package com.howe.diskmark.bench

import java.util.Locale

/** One CrystalDiskMark-style test row, e.g. SEQ1M Q8T1. */
data class BenchTest(val id: String, val random: Boolean, val blockSize: Int, val queueDepth: Int, val threads: Int) {
  val label: String
    get() = "${if (random) "RND" else "SEQ"}${sizeLabel(blockSize)}"

  val queueLabel: String
    get() = "Q${queueDepth}T$threads"

  companion object {
    /** The default profile of CrystalDiskMark 8. */
    val DEFAULT =
      listOf(
        BenchTest("seq1m_q8t1", random = false, blockSize = 1 shl 20, queueDepth = 8, threads = 1),
        BenchTest("seq1m_q1t1", random = false, blockSize = 1 shl 20, queueDepth = 1, threads = 1),
        BenchTest("rnd4k_q32t1", random = true, blockSize = 4 shl 10, queueDepth = 32, threads = 1),
        BenchTest("rnd4k_q1t1", random = true, blockSize = 4 shl 10, queueDepth = 1, threads = 1),
      )

    private fun sizeLabel(bytes: Int) = if (bytes >= 1 shl 20) "${bytes shr 20}M" else "${bytes shr 10}K"
  }
}

/** Throughput of one direction (read or write) of a test. */
data class Measurement(val bytes: Long, val ops: Long, val elapsedNs: Long, val directIo: Boolean) {
  val mbPerSec: Double
    get() = if (elapsedNs == 0L) 0.0 else bytes / 1_000_000.0 / (elapsedNs / 1e9)

  val iops: Double
    get() = if (elapsedNs == 0L) 0.0 else ops / (elapsedNs / 1e9)

  /** Average per-request latency, accounting for the requests in flight. */
  fun latencyUs(inFlight: Int): Double = if (ops == 0L) 0.0 else elapsedNs / 1000.0 * inFlight / ops
}

enum class ResultUnit(val label: String) {
  MBS("MB/s"),
  GBS("GB/s"),
  IOPS("IOPS"),
  US("μs"),
}

fun Measurement?.format(unit: ResultUnit, test: BenchTest): String {
  if (this == null) return "0.00"
  return when (unit) {
    ResultUnit.MBS -> String.format(Locale.US, "%.2f", mbPerSec)
    ResultUnit.GBS -> String.format(Locale.US, "%.3f", mbPerSec / 1000)
    ResultUnit.IOPS -> String.format(Locale.US, "%.2f", iops)
    ResultUnit.US -> String.format(Locale.US, "%.2f", latencyUs(test.queueDepth * test.threads))
  }
}

class BenchmarkException(val errno: Int, message: String) : Exception(message)
