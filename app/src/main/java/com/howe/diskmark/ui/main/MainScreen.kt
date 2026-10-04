package com.howe.diskmark.ui.main

import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.howe.diskmark.bench.BenchTest
import com.howe.diskmark.bench.Measurement
import com.howe.diskmark.bench.ResultUnit
import com.howe.diskmark.bench.format
import com.howe.diskmark.theme.DiskMarkTheme
import kotlin.math.log10

private val Green = Color(0xFF2E9E4F)
private val GreenDark = Color(0xFF1E6E36)

@Composable
fun MainScreen(modifier: Modifier = Modifier, viewModel: MainScreenViewModel = viewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  MainScreen(
    state = state,
    onRunAll = viewModel::runAll,
    onRunSingle = viewModel::runSingle,
    onCancel = viewModel::cancel,
    onCount = viewModel::setCount,
    onSize = viewModel::setSizeMb,
    onDuration = viewModel::setDuration,
    onUnit = viewModel::setUnit,
    modifier = modifier,
  )
}

@Composable
internal fun MainScreen(
  state: MainUiState,
  onRunAll: () -> Unit,
  onRunSingle: (BenchTest) -> Unit,
  onCancel: () -> Unit,
  onCount: (Int) -> Unit,
  onSize: (Int) -> Unit,
  onDuration: (Int) -> Unit,
  onUnit: (ResultUnit) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("DiskMark", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      Selector("count", "${state.count}×", listOf(1, 3, 5, 9), { "$it×" }, onCount, !state.running, Modifier.weight(1f))
      Selector("size", sizeLabel(state.sizeMb), listOf(64, 256, 1024, 4096), ::sizeLabel, onSize, !state.running, Modifier.weight(1.3f))
      Selector("duration", "${state.durationSec}s", listOf(1, 3, 5, 10), { "${it}s" }, onDuration, !state.running, Modifier.weight(1f))
      Selector("unit", state.unit.label, ResultUnit.entries, { it.label }, onUnit, true, Modifier.weight(1.2f))
    }

    // Header row: [All] | Read | Write
    Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        onClick = if (state.running) onCancel else onRunAll,
        modifier = Modifier.weight(0.9f).fillMaxHeight().testTag("btn_all"),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (state.running) Color(0xFFC62828) else GreenDark),
      ) {
        Text(if (state.running) "Stop" else "All", fontSize = 18.sp, fontWeight = FontWeight.Bold)
      }
      HeaderCell("Read (${state.unit.label})", Modifier.weight(1f))
      HeaderCell("Write (${state.unit.label})", Modifier.weight(1f))
    }

    state.tests.forEach { test ->
      val result = state.results[test.id]
      Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = { onRunSingle(test) },
          enabled = !state.running,
          modifier = Modifier.weight(0.9f).fillMaxHeight().testTag("btn_${test.id}"),
          shape = RoundedCornerShape(6.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Green),
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Shrink instead of wrapping when the system font scale is large.
            Text(test.label, fontWeight = FontWeight.Bold, maxLines = 1, autoSize = TextAutoSize.StepBased(10.sp, 16.sp))
            Text(test.queueLabel, maxLines = 1, autoSize = TextAutoSize.StepBased(9.sp, 13.sp))
          }
        }
        ResultCell(result?.read, test, state.unit, "read_${test.id}", Modifier.weight(1f))
        ResultCell(result?.write, test, state.unit, "write_${test.id}", Modifier.weight(1f))
      }
    }

    StatusBar(state.status)
    DeviceInfo(state)
  }
}

@Composable
private fun <T> Selector(
  tag: String,
  label: String,
  options: List<T>,
  optionLabel: (T) -> String,
  onSelect: (T) -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  Box(modifier) {
    OutlinedButton(
      onClick = { expanded = true },
      enabled = enabled,
      modifier = Modifier.fillMaxWidth().testTag("select_$tag"),
      shape = RoundedCornerShape(6.dp),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
    ) {
      Text(label, maxLines = 1)
    }
    // Popups are separate windows, so the resource-id flag must be re-applied for `android layout`.
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      modifier = Modifier.semantics { testTagsAsResourceId = true },
    ) {
      options.forEach { option ->
        DropdownMenuItem(
          text = { Text(optionLabel(option)) },
          onClick = {
            onSelect(option)
            expanded = false
          },
          modifier = Modifier.testTag("option_${tag}_$option"),
        )
      }
    }
  }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
  Box(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
    Text(text, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
  }
}

@Composable
private fun ResultCell(m: Measurement?, test: BenchTest, unit: ResultUnit, tag: String, modifier: Modifier) {
  // Log-scale meter like CrystalDiskMark: 1 MB/s .. 10 GB/s fills the cell.
  val fill = m?.let { (log10(it.mbPerSec.coerceAtLeast(1.0)) / 4.0).coerceIn(0.0, 1.0).toFloat() } ?: 0f
  val shape = RoundedCornerShape(6.dp)
  Box(
    modifier.fillMaxHeight().clip(shape).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, Color(0x33000000), shape),
    contentAlignment = Alignment.CenterEnd,
  ) {
    Box(Modifier.fillMaxHeight().fillMaxWidth(fill).align(Alignment.CenterStart).background(Green.copy(alpha = 0.35f)))
    Text(
      m.format(unit, test),
      modifier = Modifier.padding(horizontal = 8.dp).testTag(tag),
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      textAlign = TextAlign.End,
      autoSize = TextAutoSize.StepBased(10.sp, 22.sp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun StatusBar(status: RunStatus) {
  val text =
    when (status) {
      RunStatus.Idle -> "Ready"
      is RunStatus.Running -> status.message
      RunStatus.Done -> "Done"
      RunStatus.Cancelled -> "Cancelled"
      is RunStatus.Failed -> "Error: ${status.message}"
    }
  Column(Modifier.fillMaxWidth()) {
    if (status is RunStatus.Running) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
    Text(text, Modifier.padding(top = 6.dp).testTag("status"), style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun DeviceInfo(state: MainUiState) {
  val context = LocalContext.current
  val free = Formatter.formatFileSize(context, state.freeBytes)
  val total = Formatter.formatFileSize(context, state.totalBytes)
  val io =
    when (state.directIo) {
      null -> "—"
      true -> "O_DIRECT (cache bypassed)"
      false -> "Buffered (O_DIRECT unsupported)"
    }
  Text(
    "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n" +
      "Storage: $free free / $total\nI/O mode: $io\nPath: ${state.storagePath}",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.testTag("device_info"),
  )
}

private fun sizeLabel(mb: Int) = if (mb >= 1024) "${mb / 1024}GiB" else "${mb}MiB"

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
  DiskMarkTheme {
    MainScreen(
      state =
        MainUiState(
          results = mapOf("seq1m_q8t1" to TestResult(Measurement(2_000_000_000, 2000, 1_000_000_000, true), null)),
          status = RunStatus.Done,
        ),
      onRunAll = {},
      onRunSingle = {},
      onCancel = {},
      onCount = {},
      onSize = {},
      onDuration = {},
      onUnit = {},
      modifier = Modifier.padding(16.dp),
    )
  }
}
