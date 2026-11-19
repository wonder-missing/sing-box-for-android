package io.nekohasekai.sfa.conductor

import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import dev.jeziellago.compose.markdowntext.MarkdownText
import io.noties.markwon.core.spans.CodeSpan

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
    // MarkdownText remembers its Markwon instance without keys, so the first
    // beforeSetMarkdown lambda stays alive for this composition. Read the
    // colour through a State so a theme or dynamic-colour change still applies
    // on the next setMarkdown call.
    val codeColor = rememberUpdatedState(conductorInlineCodeColor(MaterialTheme.colorScheme).toArgb())
    MarkdownText(
        markdown = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        isTextSelectable = true,
        beforeSetMarkdown = { _, spanned ->
            // Markwon paints inline code as a filled capsule. Conductor has
            // always used a transparent background with the theme accent, so
            // replace its CodeSpan with the same monospace + colour spans the
            // table cells use.
            val spannable = spanned as? Spannable
            if (spannable != null) {
                spannable.getSpans(0, spannable.length, CodeSpan::class.java).forEach { span ->
                    val start = spannable.getSpanStart(span)
                    val end = spannable.getSpanEnd(span)
                    val flags = spannable.getSpanFlags(span)
                    spannable.removeSpan(span)
                    if (start >= 0 && end > start) {
                        spannable.setSpan(TypefaceSpan("monospace"), start, end, flags)
                        spannable.setSpan(ForegroundColorSpan(codeColor.value), start, end, flags)
                    }
                }
            }
        },
        onLinkClicked = { url ->
            if (url.startsWith("https://", ignoreCase = true) ||
                url.startsWith("http://", ignoreCase = true)
            ) {
                runCatching { uriHandler.openUri(url) }
            }
        },
    )
}
