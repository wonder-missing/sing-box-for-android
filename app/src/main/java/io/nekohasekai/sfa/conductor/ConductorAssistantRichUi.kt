package io.nekohasekai.sfa.conductor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.R
import kotlinx.coroutines.delay

@Composable
internal fun AssistantCodeBlock(segment: ConductorAssistantSegment.Code) {
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    var copied by remember(segment.code) { mutableStateOf(false) }
    val copyCodeLabel = stringResource(R.string.conductor_copy_code)
    val codeCopiedLabel = stringResource(R.string.conductor_code_copied)
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        segment.language.ifBlank {
                            stringResource(R.string.conductor_code_block)
                        },
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.6.sp,
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(segment.code))
                        copied = true
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector =
                            if (copied) {
                                Icons.Default.Check
                            } else {
                                Icons.Default.ContentCopy
                            },
                        contentDescription = if (copied) codeCopiedLabel else copyCodeLabel,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp,
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(vertical)
                        .padding(vertical = 8.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = segment.code,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = false,
                        modifier =
                            Modifier
                                .horizontalScroll(horizontal)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun AssistantTableBlock(segment: ConductorAssistantSegment.Table) {
    val scroll = rememberScrollState()
    val columns = maxOf(segment.header.size, segment.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    val widths =
        remember(segment) {
            (0 until columns).map { column ->
                val maxChars =
                    maxOf(
                        segment.header.getOrElse(column) { "" }.length,
                        segment.rows.maxOfOrNull { it.getOrElse(column) { "" }.length } ?: 0,
                    )
                (maxChars * 8 + 24).coerceIn(88, 240).dp
            }
        }
    val headerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val rowColor = MaterialTheme.colorScheme.surfaceContainerLow

    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = rowColor,
    ) {
        SelectionContainer {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scroll),
            ) {
                TableRow(
                    cells = segment.header,
                    widths = widths,
                    alignments = segment.alignments,
                    header = true,
                    background = headerColor,
                )
                segment.rows.forEach { row ->
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 1.dp,
                    )
                    TableRow(
                        cells = row,
                        widths = widths,
                        alignments = segment.alignments,
                        header = false,
                        background = rowColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    widths: List<Dp>,
    alignments: List<AssistantTableAlignment>,
    header: Boolean,
    background: Color,
) {
    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.Start,
    ) {
        widths.forEachIndexed { index, width ->
            Box(
                modifier =
                    Modifier
                        .width(width)
                        .fillMaxHeight()
                        .background(background)
                        .padding(horizontal = 8.dp, vertical = 7.dp),
            ) {
                Text(
                    text =
                        markdownAnnotated(
                            text = cells.getOrElse(index) { "" },
                            codeBackground = Color.Transparent,
                            codeColor = conductorInlineCodeColor(MaterialTheme.colorScheme),
                        ),
                    style =
                        if (header) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign =
                        when (alignments.getOrElse(index) { AssistantTableAlignment.LEFT }) {
                            AssistantTableAlignment.LEFT -> TextAlign.Start
                            AssistantTableAlignment.CENTER -> TextAlign.Center
                            AssistantTableAlignment.RIGHT -> TextAlign.End
                        },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
