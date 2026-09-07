@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package lol.alphaliu01.runningmusic.steps.dev

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sosauce.chocola.presentation.screens.settings.compenents.SettingsSwitch
import lol.alphaliu01.runningmusic.cadence.steps.StepSample
import lol.alphaliu01.runningmusic.steps.FGS_TYPE_HEALTH
import lol.alphaliu01.runningmusic.steps.FGS_TYPE_MEDIA_PLAYBACK
import lol.alphaliu01.runningmusic.steps.StepSensorCapabilities
import lol.alphaliu01.runningmusic.steps.StepSensorInfo
import lol.alphaliu01.runningmusic.steps.describe
import lol.alphaliu01.runningmusic.steps.stepPermissionsToRequest
import org.koin.androidx.compose.koinViewModel

private const val BATCH_LATENCY_60S_US = 60_000_000

/**
 * The step-recorder spike's control panel.
 *
 * Debug-only and reached only when `BuildConfig.DEBUG`, so the strings are
 * hardcoded English rather than resources: they are developer-facing, and adding
 * them to `strings.xml` would push them at translators for a screen no user will
 * ever see.
 */
@Composable
fun StepRecorderScreen() {
    val viewModel = koinViewModel<StepRecorderViewModel>()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { viewModel.onPermissionResult() }
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        SectionTitle("Sensor hardware")
        CapabilityCard(viewModel.capabilities)

        SectionTitle("Configuration")
        SettingsSwitch(
            checked = config.useWakeUpSensor,
            topDp = 24.dp,
            bottomDp = 4.dp,
            text = "Wakeup sensor",
            onCheckedChange = { viewModel.setConfig { it.copy(useWakeUpSensor = !it.useWakeUpSensor) } }
        )
        SettingsSwitch(
            checked = config.fgsType == FGS_TYPE_HEALTH,
            topDp = 4.dp,
            bottomDp = 4.dp,
            text = "health FGS type (else mediaPlayback)",
            onCheckedChange = {
                viewModel.setConfig {
                    it.copy(
                        fgsType = if (it.fgsType == FGS_TYPE_HEALTH) FGS_TYPE_MEDIA_PLAYBACK
                        else FGS_TYPE_HEALTH
                    )
                }
            }
        )
        SettingsSwitch(
            checked = config.batchLatencyUs > 0,
            topDp = 4.dp,
            bottomDp = 4.dp,
            text = "Batch at 60s latency",
            onCheckedChange = {
                viewModel.setConfig {
                    it.copy(batchLatencyUs = if (it.batchLatencyUs > 0) 0 else BATCH_LATENCY_60S_US)
                }
            }
        )
        SettingsSwitch(
            checked = config.recordAccelerometer,
            topDp = 4.dp,
            bottomDp = 24.dp,
            text = "Also record accelerometer at 50 Hz",
            onCheckedChange = { viewModel.setConfig { it.copy(recordAccelerometer = !it.recordAccelerometer) } }
        )

        SectionTitle("Recording")
        InfoCard(topDp = 24.dp, bottomDp = 24.dp) {
            if (status.isRecording) {
                Text("Recording · ${status.config?.describe().orEmpty()}")
                Text("Sensor: ${status.sensorName} (wakeUp=${status.actuallyWakeUp})")
                Text("Steps: ${status.stepCount}")
                Text("Instantaneous: ${"%.1f".format(ui.instantaneousSpm)} spm")
                Text("Average since start: ${"%.1f".format(ui.averageSpm)} spm")
                Text("File: ${status.outputPath?.substringAfterLast('/').orEmpty()} (${ui.outputBytes} B)")
            } else {
                Text("Idle")
                status.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                status.outputPath?.let { Text("Last file: ${it.substringAfterLast('/')}") }
            }
        }

        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!ui.hasPermission) {
                Button(
                    onClick = { permissionLauncher.launch(stepPermissionsToRequest()) },
                    shapes = ButtonDefaults.shapes()
                ) { Text("Grant permission") }
            }
            Button(
                onClick = { if (status.isRecording) viewModel.stop() else viewModel.start() },
                enabled = ui.hasPermission && viewModel.capabilities.hasAnyDetector,
                shapes = ButtonDefaults.shapes()
            ) { Text(if (status.isRecording) "Stop" else "Start") }
        }

        SectionTitle("Live events")
        InfoCard(topDp = 24.dp, bottomDp = 24.dp) {
            if (ui.recent.isEmpty()) {
                Text("No events yet")
            } else {
                // Newest first, so the interesting end is not below the fold.
                ui.recent.asReversed().windowedPairs().forEach { (previous, sample) ->
                    Text(
                        text = sample.describeAgainst(previous),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
        }

        SectionTitle("Files on device")
        InfoCard(topDp = 24.dp, bottomDp = 24.dp) {
            if (ui.existingRecordings.isEmpty()) {
                Text("None yet")
            } else {
                ui.existingRecordings.forEach {
                    Text(
                        text = "${it.name}  ${it.length()} B",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
        }
    }
}

@Composable
private fun CapabilityCard(capabilities: StepSensorCapabilities) {
    InfoCard(topDp = 24.dp, bottomDp = 24.dp) {
        SensorLines("Non-wakeup", capabilities.nonWakeUp)
        SensorLines("Wakeup", capabilities.wakeUp)
        Text("Step counter present: ${capabilities.hasStepCounter}")
    }
}

@Composable
private fun SensorLines(label: String, info: StepSensorInfo?) {
    if (info == null) {
        Text("$label: absent", color = MaterialTheme.colorScheme.error)
        return
    }

    Text("$label: ${info.name} · ${info.vendor}")
    Text(
        text = "  ${info.reportingMode} · FIFO ${info.fifoMaxEventCount}" +
            " (reserved ${info.fifoReservedEventCount})",
        style = MaterialTheme.typography.labelMedium.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 34.dp, vertical = 8.dp)
    )
}

@Composable
private fun InfoCard(
    topDp: androidx.compose.ui.unit.Dp,
    bottomDp: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = RoundedCornerShape(
            topStart = topDp,
            topEnd = topDp,
            bottomStart = bottomDp,
            bottomEnd = bottomDp
        )
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = { content() }
        )
    }
}

private fun List<StepSample>.windowedPairs(): List<Pair<StepSample?, StepSample>> =
    mapIndexed { index, sample -> getOrNull(index + 1) to sample }

/**
 * The two clocks side by side, which is the whole point of the live feed: a
 * delivery lag in the seconds means the events were buffered rather than live.
 */
private fun StepSample.describeAgainst(previous: StepSample?): String {
    val intervalMs = previous?.let { (sensorTimestampNs - it.sensorTimestampNs) / 1_000_000.0 }
    val lagMs = deliveryLagNs / 1_000_000.0

    return buildString {
        append("t=%.3fs".format(sensorTimestampNs / 1_000_000_000.0))
        append("  lag=%.0fms".format(lagMs))
        if (intervalMs != null) append("  Δ=%.0fms".format(intervalMs))
    }
}
