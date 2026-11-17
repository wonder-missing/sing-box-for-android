package io.nekohasekai.sfa.compose.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.humanBytes
import kotlinx.coroutines.delay

private val STATUS_CHIP_SHAPE = RoundedCornerShape(8.dp)
private val STATUS_CHIP_HEIGHT = 36.dp
private val STATUS_CHIP_TOUCH_HEIGHT = 48.dp
private val STATUS_CHIP_ICON_SIZE = 18.dp

// Below this width the VFS chip keeps only its label and dot; the upload
// total stays visible in the sharing card above the bar.
private val VFS_CHIP_FULL_TEXT_MIN_WIDTH = 400.dp

@Composable
fun ServiceStatusBar(
    visible: Boolean,
    serviceStatus: Status,
    startTime: Long?,
    groupsCount: Int,
    hasGroups: Boolean,
    onGroupsClick: () -> Unit,
    connectionsCount: Int,
    onConnectionsClick: () -> Unit,
    vfsOn: Boolean,
    vfsConnecting: Boolean,
    vfsUploadBytes: Long,
    onVfsClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
        ) {
            BoxWithConstraints {
                val compactVfs = maxWidth < VFS_CHIP_FULL_TEXT_MIN_WIDTH
                val transitionLabel =
                    when (serviceStatus) {
                        Status.Starting -> stringResource(R.string.status_starting)
                        Status.Stopping -> stringResource(R.string.status_stopping)
                        else -> null
                    }
                val vfsDescription =
                    stringResource(
                        when {
                            vfsConnecting -> R.string.vfs_status_connecting
                            vfsOn -> R.string.vfs_status_on
                            else -> R.string.vfs_status_off
                        },
                    )
                val vfsLabel =
                    if (compactVfs) {
                        stringResource(R.string.vfs_status_chip_compact)
                    } else {
                        stringResource(R.string.vfs_status_chip, humanBytes(vfsUploadBytes))
                    }
                val vfsContainerColor =
                    if (vfsOn) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                val vfsContentColor =
                    if (vfsOn) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }

                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left group: live counters.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (serviceStatus == Status.Started) {
                            StatusChip(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                onClick = onConnectionsClick,
                            ) {
                                Text(
                                    text = connectionsCount.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                ChipIcon(
                                    imageVector = Icons.Outlined.Cable,
                                    contentDescription = stringResource(R.string.title_connections),
                                )
                            }
                        }

                        if (hasGroups) {
                            StatusChip(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                onClick = onGroupsClick,
                            ) {
                                Text(
                                    text = groupsCount.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                ChipIcon(
                                    imageVector = Icons.Outlined.Layers,
                                    contentDescription = stringResource(R.string.title_groups),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Right group: session controls and sharing status.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusChip(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            onClick = onStopClick,
                        ) {
                            if (transitionLabel != null) {
                                Text(
                                    text = transitionLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            } else if (startTime != null) {
                                UptimeText(startTime = startTime)
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            ChipIcon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = stringResource(R.string.stop),
                            )
                        }

                        // The number is bytes uploaded to the consumer during the
                        // current session; tapping the chip toggles the sharing card.
                        StatusChip(
                            containerColor = vfsContainerColor,
                            contentColor = vfsContentColor,
                            onClick = onVfsClick,
                            enabled = vfsOn,
                        ) {
                            if (vfsOn) {
                                Box(
                                    modifier =
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.tertiary),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = vfsLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            if (vfsConnecting) {
                                Box(
                                    modifier = Modifier.size(STATUS_CHIP_ICON_SIZE),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier =
                                        Modifier
                                            .size(16.dp)
                                            .semantics { contentDescription = vfsDescription },
                                        strokeWidth = 2.dp,
                                        color = vfsContentColor,
                                    )
                                }
                            } else {
                                ChipIcon(
                                    imageVector = Icons.Outlined.CloudUpload,
                                    contentDescription = vfsDescription,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A pill with one fixed visual height and a larger transparent touch target.
 * Callers supply the colors so the chip can carry different meanings without
 * changing its geometry.
 */
@Composable
internal fun StatusChip(
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Box(
        modifier =
        modifier
            .height(STATUS_CHIP_TOUCH_HEIGHT)
            .defaultMinSize(minWidth = STATUS_CHIP_TOUCH_HEIGHT)
            .clip(STATUS_CHIP_SHAPE)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Row(
                modifier =
                Modifier
                    .height(STATUS_CHIP_HEIGHT)
                    .clip(STATUS_CHIP_SHAPE)
                    .background(containerColor)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

@Composable
private fun ChipIcon(
    imageVector: ImageVector,
    contentDescription: String,
) {
    Box(
        modifier = Modifier.size(STATUS_CHIP_ICON_SIZE),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(STATUS_CHIP_ICON_SIZE),
        )
    }
}

@Composable
fun UptimeText(startTime: Long, modifier: Modifier = Modifier) {
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(startTime) {
        while (true) {
            delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }

    val elapsedSeconds = ((currentTime - startTime) / 1000).coerceAtLeast(0)
    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60
    val seconds = elapsedSeconds % 60

    val formattedTime =
        if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }

    Text(
        text = formattedTime,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier,
    )
}
