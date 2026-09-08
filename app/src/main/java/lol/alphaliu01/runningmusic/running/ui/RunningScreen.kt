@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package lol.alphaliu01.runningmusic.running.ui

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sosauce.chocola.R
import com.sosauce.chocola.presentation.screens.playing.components.WavySlider
import com.sosauce.chocola.utils.selfAlignHorizontally
import com.sosauce.nekobites.animations.AnimatedFab
import lol.alphaliu01.runningmusic.cadence.ToleranceBand
import lol.alphaliu01.runningmusic.cadence.steps.Motion
import lol.alphaliu01.runningmusic.cadence.steps.TrackingMode
import lol.alphaliu01.runningmusic.running.CadenceTrackerState
import lol.alphaliu01.runningmusic.running.CadenceUnavailable
import lol.alphaliu01.runningmusic.steps.hasStepPermission
import lol.alphaliu01.runningmusic.steps.stepPermissionsToRequest
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

/**
 * Where a run is set up and watched.
 *
 * The controls are a cadence and a length, and everything else on the screen is
 * the app answering "what would that actually give me": how much of the library
 * fits, how far tracks would have to be stretched, and whether a cadence a few
 * steps away would do noticeably better. That readout is the point. Cadence
 * coverage is lumpy rather than smooth, so the number a runner would guess at is
 * often several spm away from the one their own library supports.
 */
@Composable
fun RunningScreen(onNavigateBack: () -> Unit) {
    val viewModel = koinViewModel<RunningViewModel>()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    Scaffold(
        bottomBar = {
            AnimatedFab(
                onClick = onNavigateBack,
                modifier = Modifier
                    .padding(start = 15.dp)
                    .navigationBarsPadding()
                    .selfAlignHorizontally(Alignment.Start),
                icon = R.drawable.back,
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            InfoCard(topDp = 24.dp, bottomDp = 4.dp) {
                TrackingModes(ui, onSelect = viewModel::setTrackingMode)
            }

            ValueCard(
                topDp = 4.dp,
                bottomDp = 4.dp,
                label = stringResource(R.string.running_cadence),
                value = ui.settings.targetCadence,
                unit = stringResource(R.string.running_cadence_unit),
                range = CADENCE_RANGE,
                description = stringResource(R.string.running_cadence_desc),
                onValueChange = viewModel::previewCadence,
                onValueSettled = viewModel::commitCadence
            )

            InfoCard(topDp = 4.dp, bottomDp = 4.dp) {
                Coverage(ui, onUseSuggestion = viewModel::useSuggestedCadence)
            }

            ValueCard(
                topDp = 4.dp,
                bottomDp = 4.dp,
                label = stringResource(R.string.running_length),
                value = ui.settings.runLengthMinutes,
                unit = stringResource(R.string.running_length_unit),
                range = RUN_LENGTH_RANGE,
                description = stringResource(R.string.running_length_desc),
                onValueChange = viewModel::previewRunLength,
                onValueSettled = viewModel::commitRunLength
            )

            if (ui.run.active) {
                InfoCard(topDp = 4.dp, bottomDp = 4.dp) { InProgress(ui) }
            }

            InfoCard(topDp = 4.dp, bottomDp = 4.dp) {
                if (ui.run.active) {
                    Button(onClick = viewModel::stop) {
                        Text(stringResource(R.string.running_stop))
                    }
                } else {
                    Button(
                        onClick = viewModel::start,
                        enabled = ui.plan.tracks.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.running_start))
                    }
                }
            }

            if (ui.withoutTempo > 0) {
                InfoCard(topDp = 4.dp, bottomDp = 4.dp) {
                    Muted(
                        pluralStringResource(
                            R.plurals.running_needs_analysis,
                            ui.withoutTempo,
                            ui.withoutTempo
                        )
                    )
                }
            }

            InfoCard(topDp = 4.dp, bottomDp = 24.dp) {
                Muted(stringResource(R.string.running_note))
            }
        }
    }
}

@Composable
private fun Coverage(ui: RunningUi, onUseSuggestion: () -> Unit) {
    val coverage = ui.coverage
    val plan = ui.plan

    if (coverage.accepted == 0) {
        Muted(stringResource(R.string.running_coverage_none))
    } else {
        Text(stringResource(R.string.running_coverage, coverage.accepted, coverage.total))
        Muted(
            stringResource(
                R.string.running_plan,
                elapsed(plan.filledMs),
                stretchOf(plan.worstBand)
            )
        )
        if (plan.shortfallMs > 0) {
            Muted(stringResource(R.string.running_shortfall, elapsed(plan.shortfallMs)))
        }
    }

    val suggestion = ui.suggestion
    if (suggestion != null && suggestion.worthMoving) {
        val cadence = suggestion.best.cadence.roundToInt()

        Muted(
            stringResource(
                R.string.running_suggestion,
                cadence,
                suggestion.best.coverage.accepted,
                suggestion.requested.coverage.accepted
            )
        )
        TextButton(onClick = onUseSuggestion) {
            Text(stringResource(R.string.running_suggestion_apply, cadence))
        }
    }
}

/**
 * Where the target comes from.
 *
 * Three options rather than a switch because the middle one is the interesting
 * default and the hardest to name. Measuring once and holding is safe; following
 * continuously puts a human inside a feedback loop, and is offered rather than
 * assumed.
 */
@Composable
private fun TrackingModes(ui: RunningUi, onSelect: (TrackingMode) -> Unit) {
    val context = LocalContext.current
    val selected = ui.settings.trackingMode

    // Re-read after the dialog rather than remembered from composition, since the
    // answer can change while this screen is on top of it.
    var permitted by remember { mutableStateOf(context.hasStepPermission()) }
    val request = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        permitted = context.hasStepPermission()
    }

    Text(stringResource(R.string.running_tracking))

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        TrackingMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                enabled = mode == TrackingMode.MANUAL || ui.hasStepDetector,
                shape = SegmentedButtonDefaults.itemShape(index, TrackingMode.entries.size)
            ) {
                Text(stringResource(mode.label))
            }
        }
    }

    Muted(stringResource(selected.description))

    when {
        !ui.hasStepDetector -> Muted(stringResource(R.string.running_tracking_no_sensor))

        selected == TrackingMode.MANUAL -> Unit

        !permitted -> {
            Muted(stringResource(R.string.running_tracking_permission))
            TextButton(onClick = { request.launch(stepPermissionsToRequest()) }) {
                Text(stringResource(R.string.running_tracking_permission_grant))
            }
        }

        ui.tracking.unavailable == CadenceUnavailable.REFUSED ->
            Muted(stringResource(R.string.running_tracking_refused))

        // Worth saying out loud. The non-wakeup detector stops delivering when
        // the processor suspends, which on a run with the screen off is most of
        // the time, so tracking will quietly be much worse than it looks here.
        ui.tracking.active && !ui.tracking.wakeUp ->
            Muted(stringResource(R.string.running_tracking_nonwakeup))
    }
}

private val TrackingMode.label: Int
    get() = when (this) {
        TrackingMode.MANUAL -> R.string.running_tracking_manual
        TrackingMode.MEASURE_THEN_LOCK -> R.string.running_tracking_lock
        TrackingMode.CONTINUOUS -> R.string.running_tracking_continuous
    }

private val TrackingMode.description: Int
    get() = when (this) {
        TrackingMode.MANUAL -> R.string.running_tracking_manual_desc
        TrackingMode.MEASURE_THEN_LOCK -> R.string.running_tracking_lock_desc
        TrackingMode.CONTINUOUS -> R.string.running_tracking_continuous_desc
    }

@Composable
private fun InProgress(ui: RunningUi) {
    val current = ui.run.current
    val summary = ui.run.summary

    if (ui.tracking.active) Tracking(ui.tracking)

    if (current != null) {
        Text(
            text = current.track.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Muted(
            stringResource(
                R.string.running_now_playing,
                current.bpm,
                current.stepsPerBeat,
                percent(current.speed)
            )
        )
    }

    if (summary != null) {
        Muted(
            pluralStringResource(
                R.plurals.running_remaining,
                summary.trackCount,
                elapsed(summary.remainingMs),
                summary.trackCount
            )
        )

        if (summary.shortfallMs > 0) {
            Muted(stringResource(R.string.running_shortfall, elapsed(summary.shortfallMs)))
        }
    }
}

/**
 * What the step detector currently believes.
 *
 * Shows the motion state rather than only the number, because "stopped" and
 * "walking" are the two moments when the music deliberately ignores what the
 * runner is doing, and a target that visibly refuses to move needs to say why.
 */
@Composable
private fun Tracking(tracking: CadenceTrackerState) {
    val measured = tracking.measuredSpm

    Text(
        when {
            measured == null -> stringResource(R.string.running_tracking_measuring)
            tracking.locked -> stringResource(R.string.running_tracking_locked, spm(measured))
            else -> stringResource(R.string.running_tracking_measured, spm(measured))
        }
    )

    Muted(
        stringResource(
            when (tracking.motion) {
                Motion.STARTING -> R.string.running_motion_starting
                Motion.RUNNING -> R.string.running_motion_running
                Motion.WALKING -> R.string.running_motion_walking
                Motion.STOPPED -> R.string.running_motion_stopped
                Motion.SENSOR_LOST -> R.string.running_motion_sensor_lost
            }
        )
    )
}

private fun spm(value: Double) = "%.0f".format(value)

/**
 * The stretch a set of tracks actually needed, as the two-sided figure it is.
 *
 * Not the tolerance ceiling: that is the worst the app would ever allow, and the
 * answer for a real library is usually far better than it.
 */
private fun stretchOf(band: ToleranceBand?): String {
    if (band == null) return percent(1.0)

    val up = percent(band.maxSpeedUp)
    return if (band.maxSpeedUp == band.maxSlowDown) up else "${percent(band.minSpeed)} - $up"
}

private fun percent(speed: Double) = "%.0f%%".format(speed * 100)

private fun elapsed(ms: Long) = DateUtils.formatElapsedTime(ms / 1000)

/** A labelled slider that only writes its value when the finger comes off it. */
@Composable
private fun ValueCard(
    topDp: Dp,
    bottomDp: Dp,
    label: String,
    value: Int,
    unit: String,
    range: IntRange,
    description: String,
    onValueChange: (Int) -> Unit,
    onValueSettled: () -> Unit,
) {
    val animatedValue by animateIntAsState(value)
    val sliderState = rememberSliderState(
        value = value.toFloat(),
        valueRange = range.first.toFloat()..range.last.toFloat(),
        onValueChangeFinished = onValueSettled
    )
    sliderState.onValueChange = { onValueChange(it.roundToInt()) }

    // Keeps the thumb honest when the value is set from somewhere other than
    // this slider, which is how the cadence suggestion is applied.
    LaunchedEffect(animatedValue) {
        sliderState.value = animatedValue.toFloat()
    }

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
        Column(modifier = Modifier.padding(15.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(label, modifier = Modifier.weight(1f))
                Text("$value $unit")
            }
            WavySlider(state = sliderState)
            Muted(description)
        }
    }
}

@Composable
private fun Muted(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun InfoCard(topDp: Dp, bottomDp: Dp, content: @Composable () -> Unit) {
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = { content() }
        )
    }
}
