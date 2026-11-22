package io.nekohasekai.sfa.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max

private const val CARD_WINDOW_MS = 24L * 60L * 60L * 1_000L

@Composable
fun ActivityCard(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var events by remember { mutableStateOf(emptyList<ActivityEvent>()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            events =
                withContext(Dispatchers.IO) {
                    ActivityLog.snapshot(context, now, CARD_WINDOW_MS)
                }
            delay(30_000)
        }
    }

    val service = events.count { it.lane == ActivityLane.SERVICE }
    val network = events.count { it.lane == ActivityLane.NETWORK }
    val subscription = events.count { it.lane == ActivityLane.SUBSCRIPTION }
    val group = events.count { it.lane == ActivityLane.GROUP }
    val line =
        listOf(
            stringResource(R.string.activity_summary_service, service),
            stringResource(R.string.activity_summary_network, network),
            stringResource(R.string.activity_summary_subscription, subscription, events.count { it.lane == ActivityLane.SUBSCRIPTION && it.changed == true }),
            stringResource(R.string.activity_summary_group, group),
            stringResource(R.string.activity_summary_events, events.size),
        ).joinToString(" · ")

    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.activity_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (events.isEmpty()) {
                Text(
                    text = stringResource(R.string.activity_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                ActivityDensityBars(
                    events = events,
                    start = now - CARD_WINDOW_MS,
                    end = now,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.activity_local_only),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.activity_view),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ActivityDensityBars(
    events: List<ActivityEvent>,
    start: Long,
    end: Long,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary
    val span = (end - start).coerceAtLeast(1L)
    val buckets = IntArray(24)
    events.forEach { event ->
        val index = (((event.ts - start).toFloat() / span) * 24f).toInt().coerceIn(0, 23)
        buckets[index]++
    }
    val max = max(buckets.maxOrNull() ?: 0, 1)
    Canvas(modifier = modifier) {
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * 23) / 24f).coerceAtLeast(1f)
        buckets.forEachIndexed { index, count ->
            if (count == 0) return@forEachIndexed
            val height = (size.height * (count.toFloat() / max)).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                color = color.copy(alpha = 0.55f),
                topLeft = Offset(index * (barWidth + gap), size.height - height),
                size = Size(barWidth, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()),
            )
        }
    }
}
