package io.nekohasekai.sfa.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.RangeSliderState
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val RETENTION_HOURS = 48f
private const val DEFAULT_START_HOUR = 24f
private const val DEFAULT_END_HOUR = 48f
private const val HOUR_MS = 60L * 60L * 1_000L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ActivityScreen(
    onBack: () -> Unit,
    onOpenEvidence: (ActivityEvidence) -> Unit = {},
) {
    val context = LocalContext.current
    var anchorNow by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    var rangeStart by rememberSaveable { mutableStateOf(DEFAULT_START_HOUR) }
    var rangeEnd by rememberSaveable { mutableStateOf(DEFAULT_END_HOUR) }
    var events by remember { mutableStateOf(emptyList<ActivityEvent>()) }
    var selected by remember { mutableStateOf<ActivityEvent?>(null) }
    var serviceRunningAtStart by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val scaffoldPadding = LocalScaffoldPadding.current
    val dayFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val enabled = ActivityPreferences.isEnabled(context)
    val retentionStart = anchorNow - (RETENTION_HOURS * HOUR_MS).toLong()
    val start = retentionStart + (rangeStart * HOUR_MS).toLong()
    val end = retentionStart + (rangeEnd * HOUR_MS).toLong()
    val sliderColors =
        SliderDefaults.colors(
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        )
    val sliderState =
        remember {
            RangeSliderState(
                activeRangeStart = DEFAULT_START_HOUR,
                activeRangeEnd = DEFAULT_END_HOUR,
                steps = 0,
                valueRange = 0f..RETENTION_HOURS,
            )
        }
    LaunchedEffect(sliderState) {
        snapshotFlow { sliderState.activeRangeStart to sliderState.activeRangeEnd }
            .collect { (newStart, newEnd) ->
                rangeStart = newStart
                rangeEnd = newEnd
            }
    }
    LaunchedEffect(rangeStart, rangeEnd) {
        if (sliderState.activeRangeStart != rangeStart) sliderState.activeRangeStart = rangeStart
        if (sliderState.activeRangeEnd != rangeEnd) sliderState.activeRangeEnd = rangeEnd
    }

    LaunchedEffect(rangeStart, rangeEnd, anchorNow, enabled) {
        while (true) {
            val loaded =
                withContext(Dispatchers.IO) {
                    if (enabled) {
                        ActivityLog.serviceRunningAt(context, start) to
                            ActivityLog.snapshot(context, end, end - start)
                    } else {
                        false to emptyList()
                    }
                }
            serviceRunningAtStart = loaded.first
            events = loaded.second
            delay(30_000)
        }
    }

    fun focusAround(event: ActivityEvent) {
        val focusStart = (event.ts - 15L * 60_000L).coerceAtLeast(retentionStart)
        val focusEnd = (event.ts + 15L * 60_000L).coerceAtMost(anchorNow)
        val safeEnd = maxOf(focusEnd, focusStart + 30L * 60_000L)
        rangeStart = ((focusStart - retentionStart).toFloat() / HOUR_MS).coerceIn(0f, RETENTION_HOURS)
        rangeEnd = ((safeEnd - retentionStart).toFloat() / HOUR_MS).coerceIn(0f, RETENTION_HOURS)
        selected = null
    }

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.activity_screen_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.content_description_back),
                    )
                }
            },
            actions = {
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteSweep,
                        contentDescription = stringResource(R.string.activity_clear),
                    )
                }
            },
        )
    }

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 16.dp,
                start = 16.dp,
                end = 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.activity_range_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    anchorNow = System.currentTimeMillis()
                    rangeStart = DEFAULT_START_HOUR
                    rangeEnd = DEFAULT_END_HOUR
                },
            ) {
                Text(stringResource(R.string.activity_range_reset))
            }
        }
        RangeSlider(
            state = sliderState,
            modifier = Modifier.fillMaxWidth(),
            colors = sliderColors,
            track = { state ->
                SliderDefaults.Track(
                    rangeSliderState = state,
                    colors = sliderColors,
                    enabled = true,
                    drawStopIndicator = {},
                )
            },
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.activity_range_start, dayFormat.format(Date(start))),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.activity_range_end, dayFormat.format(Date(end))),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!enabled) {
            Text(
                text = stringResource(R.string.activity_disabled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ActivitySummary(events)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    ActivityLegend(showPowerStates = events.any { it.lane == ActivityLane.POWER })
                    Spacer(Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        ActivityScatterChart(
                            events = events,
                            windowStart = start,
                            windowEnd = end,
                            onEventTap = { selected = it },
                            selectedEventId = selected?.id,
                            serviceRunningAtStart = serviceRunningAtStart,
                            height = 320,
                        )
                        if (events.isEmpty()) {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(R.string.activity_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(R.string.activity_empty_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Text(
                text = stringResource(R.string.activity_tap_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    var nearbyCount by remember { mutableStateOf(0) }
    LaunchedEffect(selected?.id) {
        val current = selected ?: return@LaunchedEffect
        nearbyCount =
            withContext(Dispatchers.IO) {
                ActivityLog.snapshot(context, current.ts + 10L * 60_000L, 20L * 60_000L)
                    .count { it.id != current.id }
            }
    }

    if (selected != null) {
        val current = selected!!
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            ActivityDetailContent(
                event = current,
                nearbyCount = nearbyCount,
                onFocus = { focusAround(current) },
                onOpenEvidence = {
                    selected = null
                    onOpenEvidence(it)
                },
            )
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.activity_clear)) },
            text = { Text(stringResource(R.string.activity_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        ActivityLog.clear(context)
                        events = emptyList()
                    },
                ) {
                    Text(stringResource(R.string.activity_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivitySummary(events: List<ActivityEvent>) {
    val service = events.count { it.lane == ActivityLane.SERVICE }
    val network = events.count { it.lane == ActivityLane.NETWORK }
    val subscription = events.count { it.type == ActivityTypes.PROFILE_UPDATE }
    val changed = events.count { it.type == ActivityTypes.PROFILE_UPDATE && it.changed == true }
    val group = events.count { it.type == ActivityTypes.GROUP_SWITCH }
    val connection = events.filter { it.lane == ActivityLane.CONNECTION }.sumOf { it.count }
    val obstacles = events.count { it.obstacle }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ActivitySummaryChip(stringResource(R.string.activity_summary_service, service))
        ActivitySummaryChip(stringResource(R.string.activity_summary_network, network))
        ActivitySummaryChip(stringResource(R.string.activity_summary_subscription, subscription, changed))
        ActivitySummaryChip(stringResource(R.string.activity_summary_group, group))
        ActivitySummaryChip(stringResource(R.string.activity_summary_connection, connection))
        ActivitySummaryChip(stringResource(R.string.activity_summary_obstacle, obstacles))
    }
}

@Composable
private fun ActivitySummaryChip(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ActivityDetailContent(
    event: ActivityEvent,
    nearbyCount: Int,
    onFocus: () -> Unit,
    onOpenEvidence: (ActivityEvidence) -> Unit,
) {
    val format = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val connection = event.type == ActivityTypes.CONNECTION_ERROR
    val result =
        when (event.type) {
            ActivityTypes.PROFILE_UPDATE ->
                if (event.ok == false) {
                    stringResource(R.string.activity_result_failed)
                } else {
                    stringResource(R.string.activity_result_ok)
                }
            else -> null
        }
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text(
            text = activityEventTitle(event),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = format.format(Date(event.ts)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (event.lastTs > event.ts + 60_000L) {
            Text(
                text =
                stringResource(
                    R.string.activity_detail_last_seen,
                    format.format(Date(event.lastTs)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        event.group?.let { DetailRow(stringResource(R.string.activity_detail_group), it) }
        event.from?.let { DetailRow(stringResource(R.string.activity_detail_from), it) }
        event.to?.let { DetailRow(stringResource(R.string.activity_detail_to), it) }
        if (connection) {
            event.subject?.let { DetailRow(stringResource(R.string.activity_detail_outbound), it) }
            event.reason?.let { DetailRow(stringResource(R.string.activity_detail_error_class), it) }
        } else {
            event.reason?.let { DetailRow(stringResource(R.string.activity_detail_reason), it) }
        }
        result?.let { DetailRow(stringResource(R.string.activity_detail_result), it) }
        event.ms?.let { DetailRow(stringResource(R.string.activity_detail_delay), "$it ms") }
        if (event.available != null && event.total != null) {
            DetailRow(
                stringResource(R.string.activity_detail_available),
                "${event.available} / ${event.total}",
            )
        }
        if (connection || event.count > 1) {
            DetailRow(stringResource(R.string.activity_detail_count), event.count.toString())
        }
        event.detail?.let { DetailRow(stringResource(R.string.activity_detail_note), it) }
        if (nearbyCount > 0) {
            Text(
                text = stringResource(R.string.activity_detail_nearby, nearbyCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onFocus) {
                Text(stringResource(R.string.activity_detail_focus))
            }
            event.evidence?.let { evidence ->
                TextButton(onClick = { onOpenEvidence(evidence) }) {
                    Text(stringResource(R.string.activity_detail_open_report))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp).padding(end = 12.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun activityEventTitle(event: ActivityEvent): String =
    when (event.type) {
        ActivityTypes.SERVICE_START -> stringResource(R.string.activity_event_service_start)
        ActivityTypes.SERVICE_STOP -> stringResource(R.string.activity_event_service_stop)
        ActivityTypes.SERVICE_ALERT -> stringResource(R.string.activity_event_service_alert, event.subject.orEmpty())
        ActivityTypes.VPN_REVOKED -> stringResource(R.string.activity_event_vpn_revoked)
        ActivityTypes.SERVICE_RESTORE -> stringResource(R.string.activity_event_service_restore, event.subject.orEmpty())
        ActivityTypes.CONFIG_RELOAD -> stringResource(R.string.activity_event_config_reload)
        ActivityTypes.NETWORK_CHANGE ->
            stringResource(
                R.string.activity_event_network,
                event.subject ?: stringResource(R.string.activity_legend_network),
            )
        ActivityTypes.NETWORK_LOST -> stringResource(R.string.activity_event_network_lost)
        ActivityTypes.CONNECTION_ERROR -> stringResource(R.string.activity_event_connection_error)
        ActivityTypes.TAILSCALE_STATE ->
            stringResource(
                R.string.activity_event_tailscale,
                event.subject ?: stringResource(R.string.activity_legend_tailscale),
            )
        ActivityTypes.POWER_STATE -> stringResource(R.string.activity_event_power, event.subject.orEmpty())
        ActivityTypes.TELEMETRY_UPLOAD -> stringResource(R.string.activity_event_telemetry)
        ActivityTypes.PROFILE_UPDATE ->
            stringResource(
                R.string.activity_event_subscription,
                event.subject ?: stringResource(R.string.activity_legend_subscription),
            )
        ActivityTypes.PROFILE_SWITCH -> stringResource(R.string.activity_event_profile_switch, event.subject.orEmpty())
        ActivityTypes.GROUP_SWITCH ->
            stringResource(
                R.string.activity_event_group,
                event.group ?: event.subject ?: stringResource(R.string.activity_legend_group),
            )
        ActivityTypes.GROUP_UNAVAILABLE ->
            stringResource(R.string.activity_event_group_unavailable, event.subject.orEmpty())
        ActivityTypes.GROUP_STUCK ->
            stringResource(R.string.activity_event_group_stuck, event.subject.orEmpty())
        ActivityTypes.APP_INSTALL -> stringResource(R.string.activity_event_install, event.subject.orEmpty())
        ActivityTypes.APP_UPDATE -> stringResource(R.string.activity_event_update, event.subject.orEmpty())
        ActivityTypes.CRASH -> stringResource(R.string.activity_event_crash)
        ActivityTypes.OOM -> stringResource(R.string.activity_event_oom)
        ActivityTypes.PROCESS_EXIT -> stringResource(R.string.activity_event_process_exit, event.subject.orEmpty())
        else -> event.type
    }
