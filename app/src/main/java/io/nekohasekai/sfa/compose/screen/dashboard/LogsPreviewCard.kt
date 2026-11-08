package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.screen.log.LogEntryData
import io.nekohasekai.sfa.compose.screen.log.LogLevel
import io.nekohasekai.sfa.compose.util.AnsiColorUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * Dashboard preview: only the latest three lines, refreshed every three
 * seconds while this card is composed. The full Logs screen keeps its own
 * richer controls.
 */
@Composable
fun LogsPreviewCard(
    recentLogs: StateFlow<List<LogEntryData>>,
    onOpenLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview by produceState(initialValue = recentLogs.value.takeLast(3), recentLogs) {
        while (true) {
            value = recentLogs.value.takeLast(3)
            delay(3_000)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpenLogs),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.title_log),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (preview.isEmpty()) {
                Text(
                    text = stringResource(R.string.logs_preview_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                preview.forEach { line ->
                    Text(
                        text = AnsiColorUtils.stripAnsi(line.message),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = logColor(line.level),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun logColor(level: LogLevel) =
    when (level) {
        LogLevel.PANIC, LogLevel.FATAL, LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
