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
import lol.alphaliu01.runningmusic.cadence.Fold
import lol.alphaliu01.runningmusic.cadence.ToleranceBand
import lol.alphaliu01.runningmusic.cadence.steps.Motion
import lol.alphaliu01.runningmusic.cadence.steps.TrackingMode
import lol.alphaliu01.runningmusic.running.CadenceTrackerState
import lol.alphaliu01.runningmusic.running.CadenceUnavailable
import lol.alphaliu01.runningmusic.steps.hasStepPermission
import lol.alphaliu01.runningmusic.steps.stepPermissionsToRequest
import org.koin.androidx.compose.koinViewModel
import kotlin.math.abs
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

            StretchCard(
                topDp = 4.dp,
                bottomDp = 4.dp,
                tolerance = ui.settings.tolerance,
                onSpeedUpChange = { viewModel.previewMaxSpeedUp(stretchRatio(it)) },
                onSlowDownChange = { viewModel.previewMaxSlowDown(stretchRatio(it)) },
                onSettled = viewModel::commitTolerance,
            )

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

    // Which sensor the cadence is actually coming from. Worth saying, because a
    // detector that fires on a fixed tick reads the tick as the cadence and
    // nothing else on this screen would give it away.
    if (ui.tracking.active) {
        Muted(
            stringResource(
                if (ui.tracking.counting) R.string.running_tracking_counted
                else R.string.running_tracking_timed
            )
        )
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

    if (ui.tracking.active) Tracking(ui.tracking, ui.settings.targetCadence)

    if (current != null) {
        Text(
            text = current.track.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // A run is a mode the player is in, so the track playing is not always
        // one the run chose. When the runner has picked something themselves it
        // may have no analysed tempo at all, or one the cadence cannot reach
        // within their stretch limits, and either way the speed on its own would
        // look like a bug rather than a decision.
        if (current.bpm <= 0.0) {
            Muted(stringResource(R.string.running_unmatched))
        } else {
            Muted(
                stringResource(
                    R.string.running_now_playing,
                    current.bpm,
                    gait(current.fold),
                    percent(current.speed)
                )
            )

            if (!ui.settings.tolerance.accepts(current.speed)) {
                Muted(stringResource(R.string.running_past_your_limit))
            }
        }
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
 *
 * @param target the cadence the music is actually matched to, which is not the
 * measurement and is not meant to be. The target follows at a few spm a minute
 * so that a runner is not chased by their own music, so the two legitimately sit
 * apart for minutes after a change of pace. Showing only the measurement, which
 * this used to do even in the "matched to your N spm" line, made a track playing
 * correctly at the older target look like arithmetic that did not add up.
 */
@Composable
private fun Tracking(tracking: CadenceTrackerState, target: Int) {
    val measured = tracking.measuredSpm

    Text(
        when {
            measured == null -> stringResource(R.string.running_tracking_measuring)
            tracking.locked -> stringResource(R.string.running_tracking_locked, target)
            else -> stringResource(R.string.running_tracking_measured, spm(measured))
        }
    )

    if (measured != null && abs(target - measured) >= 1.0) {
        Muted(stringResource(R.string.running_tracking_catching_up, spm(measured)))
    }

    Muted(
        stringResource(
            when (tracking.motion) {
                Motion.STARTING -> R.string.running_motion_starting
                Motion.MOVING -> R.string.running_motion_moving
                Motion.IDLING -> R.string.running_motion_idling
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

/**
 * How the runner's steps line up with the beat.
 *
 * Three cases rather than one number because a fold below unity is not "0.5
 * steps per beat" to anybody: it is stepping every other beat, which is what
 * lets a 125 bpm track carry a 62 spm walk.
 */
@Composable
private fun gait(fold: Fold): String {
    val (steps, beats) = fold.stepsToBeats
    return when {
        beats > 1 -> stringResource(R.string.running_gait_half)
        steps == 1 -> stringResource(R.string.running_gait_single)
        else -> stringResource(R.string.running_gait_multi, steps)
    }
}

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

/**
 * The two halves of the stretch tolerance, in one card because they are one
 * decision.
 *
 * Split rather than offered as a single number because the two directions are
 * not the same trade: speeding a track up adds energy that suits running, while
 * slowing one down tends to drag. Someone who will happily take a track 15%
 * fast may want almost nothing taken slow, and a symmetric control cannot say
 * that.
 */
@Composable
private fun StretchCard(
    topDp: Dp,
    bottomDp: Dp,
    tolerance: ToleranceBand,
    onSpeedUpChange: (Int) -> Unit,
    onSlowDownChange: (Int) -> Unit,
    onSettled: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.running_stretch))

            StretchSlider(
                label = stringResource(R.string.running_stretch_faster),
                percent = stretchPercent(tolerance.maxSpeedUp),
                onChange = onSpeedUpChange,
                onSettled = onSettled,
            )
            StretchSlider(
                label = stringResource(R.string.running_stretch_slower),
                percent = stretchPercent(tolerance.maxSlowDown),
                onChange = onSlowDownChange,
                onSettled = onSettled,
            )

            Muted(stringResource(R.string.running_stretch_desc))
        }
    }
}

@Composable
private fun StretchSlider(
    label: String,
    percent: Int,
    onChange: (Int) -> Unit,
    onSettled: () -> Unit,
) {
    val animated by animateIntAsState(percent)
    val sliderState = rememberSliderState(
        value = percent.toFloat(),
        valueRange = STRETCH_PERCENT_RANGE.first.toFloat()..STRETCH_PERCENT_RANGE.last.toFloat(),
        onValueChangeFinished = onSettled
    )
    sliderState.onValueChange = { onChange(it.roundToInt()) }

    LaunchedEffect(animated) { sliderState.value = animated.toFloat() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.running_stretch_value, percent))
    }
    WavySlider(state = sliderState)
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
