package lol.alphaliu01.runningmusic.analysis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sosauce.chocola.R
import com.sosauce.chocola.presentation.screens.settings.compenents.SettingsSwitch
import org.koin.androidx.compose.koinViewModel

/**
 * Where a user starts the library-wide tempo pass and watches it run.
 *
 * Deliberately plain. The interesting decisions in this feature are all below
 * the surface, and a screen that tried to explain them would be longer than the
 * thing it explains; the one thing worth saying out loud is why some tracks come
 * back without a tempo, which is the note at the bottom.
 */
@Composable
fun TempoAnalysisScreen() {
    val viewModel = koinViewModel<TempoAnalysisViewModel>()
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        InfoCard(topDp = 24.dp, bottomDp = 4.dp) {
            Text(
                text = stringResource(R.string.tempo_analysis_desc),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        InfoCard(topDp = 4.dp, bottomDp = 4.dp) {
            val coverage = ui.coverage

            Text(
                stringResource(
                    R.string.tempo_analysis_known,
                    coverage.known,
                    coverage.total
                )
            )

            if (coverage.pending > 0) {
                Muted(stringResource(R.string.tempo_analysis_pending, coverage.pending))
            }

            if (coverage.unreadable > 0) {
                Muted(stringResource(R.string.tempo_analysis_unknown, coverage.unreadable))
            }
        }

        if (ui.progress.running) {
            InfoCard(topDp = 4.dp, bottomDp = 4.dp) {
                val progress = ui.progress

                Text(
                    text = progress.current
                        ?: stringResource(R.string.tempo_analysis_starting),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (progress.total > 0) {
                    Muted("${progress.done} / ${progress.total}")

                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            }
        }

        InfoCard(topDp = 4.dp, bottomDp = 4.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.progress.running) {
                    Button(onClick = viewModel::cancel) {
                        Text(stringResource(R.string.tempo_analysis_cancel))
                    }
                } else {
                    Button(
                        onClick = viewModel::start,
                        enabled = ui.coverage.pending > 0
                    ) {
                        Text(stringResource(R.string.tempo_analysis_start))
                    }
                }

                OutlinedButton(
                    onClick = viewModel::reanalyse,
                    enabled = !ui.progress.running && ui.coverage.total > 0
                ) {
                    Text(stringResource(R.string.tempo_analysis_redo))
                }
            }

            Muted(stringResource(R.string.tempo_analysis_redo_desc))
        }

        SettingsSwitch(
            checked = ui.onlyWhileCharging,
            topDp = 4.dp,
            bottomDp = 4.dp,
            text = stringResource(R.string.tempo_analysis_charging),
            optionalDescription = R.string.tempo_analysis_charging_desc,
            onCheckedChange = { viewModel.setOnlyWhileCharging(!ui.onlyWhileCharging) }
        )

        InfoCard(topDp = 4.dp, bottomDp = 24.dp) {
            Muted(stringResource(R.string.tempo_analysis_note))
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
