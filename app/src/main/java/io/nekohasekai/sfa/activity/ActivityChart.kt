package io.nekohasekai.sfa.activity

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class ActivityColors(
    val service: Color,
    val network: Color,
    val tailscale: Color,
    val telemetry: Color,
    val subscription: Color,
    val urlTest: Color,
    val group: Color,
    val version: Color,
    val obstacle: Color,
    val axis: Color,
    val muted: Color,
    val surface: Color,
)

@Composable
internal fun activityColors(): ActivityColors {
    val scheme = MaterialTheme.colorScheme
    return ActivityColors(
        service = scheme.primary,
        network = scheme.secondary,
        tailscale = scheme.tertiary,
        telemetry = scheme.primary.copy(alpha = 0.55f),
        subscription = scheme.tertiary,
        urlTest = scheme.onSurfaceVariant,
        group = scheme.secondary,
        version = scheme.onSurface,
        obstacle = scheme.error,
        axis = scheme.outlineVariant,
        muted = scheme.onSurfaceVariant,
        surface = scheme.surface,
    )
}

private data class PlotPoint(
    val event: ActivityEvent,
    val x: Float,
    val lane: Int,
)

private fun ActivityLane.color(colors: ActivityColors): Color = when (this) {
    ActivityLane.SERVICE -> colors.service
    ActivityLane.NETWORK -> colors.network
    ActivityLane.TAILSCALE -> colors.tailscale
    ActivityLane.TELEMETRY -> colors.telemetry
    ActivityLane.SUBSCRIPTION -> colors.subscription
    ActivityLane.URLTEST -> colors.urlTest
    ActivityLane.GROUP -> colors.group
    ActivityLane.VERSION -> colors.version
}

@Composable
fun ActivityScatterChart(
    events: List<ActivityEvent>,
    windowStart: Long,
    windowEnd: Long,
    onEventTap: (ActivityEvent) -> Unit,
    selectedEventId: Long? = null,
    serviceRunningAtStart: Boolean = false,
    modifier: Modifier = Modifier,
    height: Int = 300,
    showAxis: Boolean = true,
) {
    val colors = activityColors()
    val density = LocalDensity.current
    val axisColor = colors.axis
    val mutedArgb = colors.muted.toArgb()
    val labelSize = with(density) { 9.sp.toPx() }
    val textPaint =
        remember(mutedArgb, labelSize) {
            Paint().apply {
                isAntiAlias = true
                color = mutedArgb
                textSize = labelSize
                textAlign = Paint.Align.CENTER
            }
        }
    val labelFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val laneCount = ActivityLane.entries.size + 1
    val nowLabel = stringResource(R.string.activity_now)

    fun PlotPoint.position(size: androidx.compose.ui.geometry.Size, density: Density): Offset = with(density) {
        val left = 6.dp.toPx()
        val right = size.width - 6.dp.toPx()
        val top = 6.dp.toPx()
        val bottom = if (showAxis) size.height - 22.dp.toPx() else size.height - 6.dp.toPx()
        val laneHeight = (bottom - top) / laneCount
        Offset(left + x * (right - left), top + (lane + 0.5f) * laneHeight)
    }

    val plotPoints =
        remember(events, windowStart, windowEnd) {
            val span = (windowEnd - windowStart).coerceAtLeast(1L)
            buildList {
                events.forEach { event ->
                    val x = ((event.ts - windowStart).toFloat() / span).coerceIn(0f, 1f)
                    add(PlotPoint(event, x, event.lane.ordinal))
                    if (event.obstacle) add(PlotPoint(event, x, laneCount - 1))
                }
            }
        }

    Canvas(
        modifier =
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .pointerInput(plotPoints, windowStart, windowEnd) {
                detectTapGestures { tap ->
                    val area = androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat())
                    val hit =
                        plotPoints.minByOrNull { point ->
                            val position = point.position(area, this)
                            (position - tap).getDistance()
                        }
                    if (hit != null) {
                        val position = hit.position(area, this)
                        if ((position - tap).getDistance() <= 22.dp.toPx()) {
                            onEventTap(hit.event)
                        }
                    }
                }
            },
    ) {
        val left = 6.dp.toPx()
        val right = size.width - 6.dp.toPx()
        val top = 6.dp.toPx()
        val bottom = if (showAxis) size.height - 22.dp.toPx() else size.height - 6.dp.toPx()
        val laneHeight = (bottom - top) / laneCount
        fun laneY(index: Int) = top + (index + 0.5f) * laneHeight
        fun xAt(fraction: Float) = left + fraction * (right - left)

        if (showAxis) {
            drawLine(axisColor, Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
        }

        // Service is a continuous state: draw the session line, then its points.
        val service = events.filter { it.lane == ActivityLane.SERVICE }.sortedBy { it.ts }
        var openAt: Long? = if (serviceRunningAtStart) windowStart else null
        service.forEach { event ->
            when (event.type) {
                "service_start" -> openAt = event.ts
                "service_stop" -> {
                    val start = openAt
                    if (start != null) {
                        val startX = xAt(((start - windowStart).toFloat() / (windowEnd - windowStart).toFloat()).coerceIn(0f, 1f))
                        val endX = xAt(((event.ts - windowStart).toFloat() / (windowEnd - windowStart).toFloat()).coerceIn(0f, 1f))
                        drawLine(colors.service, Offset(startX, laneY(0)), Offset(endX, laneY(0)), 2.dp.toPx())
                        openAt = null
                    }
                }
            }
        }
        openAt?.let { start ->
            val startX = xAt(((start - windowStart).toFloat() / (windowEnd - windowStart).toFloat()).coerceIn(0f, 1f))
            drawLine(colors.service, Offset(startX, laneY(0)), Offset(right, laneY(0)), 2.dp.toPx())
        }

        events.forEach { event ->
            val x = xAt(((event.ts - windowStart).toFloat() / (windowEnd - windowStart).toFloat()).coerceIn(0f, 1f))
            val y = laneY(event.lane.ordinal)
            drawActivityMark(event, Offset(x, y), event.lane, colors)
            if (event.obstacle) {
                drawCircle(colors.obstacle, 2.6.dp.toPx(), Offset(x, laneY(laneCount - 1)))
            }
        }

        selectedEventId?.let { id ->
            plotPoints.firstOrNull { it.event.id == id }?.let { point ->
                val position = point.position(size, this)
                // Ring only: the original marker shape must stay readable.
                drawCircle(
                    colors.service,
                    7.dp.toPx(),
                    position,
                    style = Stroke(1.5.dp.toPx()),
                )
            }
        }

        if (showAxis) {
            val rightNow = windowEnd >= System.currentTimeMillis() - 2 * 60_000L
            val ticks = listOf(0f, 1f / 3f, 2f / 3f, 1f)
            ticks.forEachIndexed { index, fraction ->
                val text =
                    if (index == ticks.lastIndex && rightNow) {
                        nowLabel
                    } else {
                        labelFormat.format(Date(windowStart + ((windowEnd - windowStart).toFloat() * fraction).toLong()))
                    }
                textPaint.textAlign =
                    when (index) {
                        0 -> Paint.Align.LEFT
                        ticks.lastIndex -> Paint.Align.RIGHT
                        else -> Paint.Align.CENTER
                    }
                drawContext.canvas.nativeCanvas.drawText(
                    text,
                    xAt(fraction),
                    size.height - 5.dp.toPx(),
                    textPaint,
                )
            }
        }
    }
}

private fun DrawScope.drawActivityMark(
    event: ActivityEvent,
    center: Offset,
    lane: ActivityLane,
    colors: ActivityColors,
) {
    val color = lane.color(colors)
    when (lane) {
        ActivityLane.SERVICE -> {
            drawCircle(colors.surface, 4.6.dp.toPx(), center)
            drawCircle(color, 3.2.dp.toPx(), center)
        }
        ActivityLane.NETWORK -> drawDiamond(center, 3.6.dp.toPx(), color)
        ActivityLane.TAILSCALE -> drawTriangle(center, 4.dp.toPx(), color)
        ActivityLane.TELEMETRY ->
            drawCircle(color, 1.6.dp.toPx(), center)
        ActivityLane.SUBSCRIPTION -> {
            if (event.ok == false) {
                drawCross(center, 3.dp.toPx(), colors.obstacle)
            } else if (event.changed == true) {
                drawCircle(color, 3.2.dp.toPx(), center)
            } else {
                drawCircle(color, 3.2.dp.toPx(), center, style = Stroke(1.2.dp.toPx()))
            }
        }
        ActivityLane.URLTEST -> {
            if (event.ok == false) {
                drawCross(center, 2.8.dp.toPx(), colors.obstacle)
            } else {
                drawLine(
                    color,
                    Offset(center.x, center.y - 2.2.dp.toPx()),
                    Offset(center.x, center.y + 2.2.dp.toPx()),
                    1.dp.toPx(),
                )
            }
        }
        ActivityLane.GROUP -> {
            drawCircle(colors.surface, 4.6.dp.toPx(), center)
            drawDiamond(center, 3.4.dp.toPx(), color)
        }
        ActivityLane.VERSION -> drawTriangle(center, 4.2.dp.toPx(), color)
    }
}

private fun DrawScope.drawDiamond(center: Offset, radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + radius, center.y)
        lineTo(center.x, center.y + radius)
        lineTo(center.x - radius, center.y)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawTriangle(center: Offset, radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + radius * 0.9f, center.y + radius * 0.8f)
        lineTo(center.x - radius * 0.9f, center.y + radius * 0.8f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawCross(center: Offset, radius: Float, color: Color) {
    drawLine(
        color,
        Offset(center.x - radius, center.y - radius),
        Offset(center.x + radius, center.y + radius),
        1.4.dp.toPx(),
    )
    drawLine(
        color,
        Offset(center.x - radius, center.y + radius),
        Offset(center.x + radius, center.y - radius),
        1.4.dp.toPx(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityLegend(modifier: Modifier = Modifier) {
    val colors = activityColors()
    val items =
        listOf(
            ActivityLane.SERVICE to R.string.activity_legend_service,
            ActivityLane.NETWORK to R.string.activity_legend_network,
            ActivityLane.TAILSCALE to R.string.activity_legend_tailscale,
            ActivityLane.TELEMETRY to R.string.activity_legend_telemetry,
            ActivityLane.SUBSCRIPTION to R.string.activity_legend_subscription,
            ActivityLane.URLTEST to R.string.activity_legend_urltest,
            ActivityLane.GROUP to R.string.activity_legend_group,
            ActivityLane.VERSION to R.string.activity_legend_version,
        )
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        items.forEach { (lane, label) ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Canvas(Modifier.size(10.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    drawLegendMark(lane, colors, center)
                }
                Spacer(Modifier.width(3.dp))
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Canvas(Modifier.size(10.dp)) {
                drawCross(Offset(size.width / 2f, size.height / 2f), 3.2.dp.toPx(), colors.obstacle)
            }
            Spacer(Modifier.width(3.dp))
            Text(
                text = stringResource(R.string.activity_legend_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Canvas(Modifier.size(10.dp)) {
                drawCircle(colors.obstacle, 3.dp.toPx(), Offset(size.width / 2f, size.height / 2f))
            }
            Spacer(Modifier.width(3.dp))
            Text(
                text = stringResource(R.string.activity_legend_obstacle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun DrawScope.drawLegendMark(
    lane: ActivityLane,
    colors: ActivityColors,
    center: Offset,
) {
    val color = lane.color(colors)
    when (lane) {
        ActivityLane.SERVICE -> drawCircle(color, 3.dp.toPx(), center)
        ActivityLane.NETWORK -> drawDiamond(center, 3.4.dp.toPx(), color)
        ActivityLane.TAILSCALE -> drawTriangle(center, 4.dp.toPx(), color)
        ActivityLane.TELEMETRY -> drawCircle(color, 1.8.dp.toPx(), center)
        ActivityLane.SUBSCRIPTION ->
            drawCircle(color, 3.2.dp.toPx(), center, style = Stroke(1.2.dp.toPx()))
        ActivityLane.URLTEST ->
            drawLine(
                color,
                Offset(center.x, center.y - 2.2.dp.toPx()),
                Offset(center.x, center.y + 2.2.dp.toPx()),
                1.dp.toPx(),
            )
        ActivityLane.GROUP -> drawDiamond(center, 3.4.dp.toPx(), color)
        ActivityLane.VERSION -> drawTriangle(center, 4.dp.toPx(), color)
    }
}
