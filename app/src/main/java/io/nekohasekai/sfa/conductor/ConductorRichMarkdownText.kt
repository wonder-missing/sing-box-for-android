package io.nekohasekai.sfa.conductor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import dev.jeziellago.compose.markdowntext.MarkdownText

/**
 * CommonMark for message text. Images, SVG, fenced code and tables are split
 * out before this renderer so they can use the Conductor-specific blocks.
 */
@Composable
internal fun ConductorRichMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    MarkdownText(
        markdown = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        isTextSelectable = true,
        onLinkClicked = { url ->
            if (url.startsWith("https://", ignoreCase = true) ||
                url.startsWith("http://", ignoreCase = true)
            ) {
                runCatching { uriHandler.openUri(url) }
            }
        },
    )
}
