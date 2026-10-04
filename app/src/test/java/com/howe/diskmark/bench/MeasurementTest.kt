package com.howe.diskmark.bench

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementTest {
  private val rnd4kQ32 = BenchTest.DEFAULT[2]

  @Test
  fun throughputAndIops() {
    // 1000 x 4 KiB ops in 0.5 s
    val m = Measurement(bytes = 4096_000, ops = 1000, elapsedNs = 500_000_000, directIo = true)
    assertEquals(8.192, m.mbPerSec, 1e-9)
    assertEquals(2000.0, m.iops, 1e-9)
    // 32 requests in flight -> 32 / 2000 IOPS = 16 ms
    assertEquals(16_000.0, m.latencyUs(32), 1e-6)
  }

  @Test
  fun formatting() {
    val m = Measurement(bytes = 1_234_560_000, ops = 1000, elapsedNs = 1_000_000_000, directIo = true)
    assertEquals("1234.56", m.format(ResultUnit.MBS, rnd4kQ32))
    assertEquals("1.235", m.format(ResultUnit.GBS, rnd4kQ32))
    assertEquals("0.00", null.format(ResultUnit.MBS, rnd4kQ32))
  }

  @Test
  fun labels() {
    assertEquals(listOf("SEQ1M Q8T1", "SEQ1M Q1T1", "RND4K Q32T1", "RND4K Q1T1"), BenchTest.DEFAULT.map { "${it.label} ${it.queueLabel}" })
  }
}
