package io.nekohasekai.sfa.conductor

import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.telemetry.TelemetryEventLog
import io.nekohasekai.sfa.telemetry.TelemetryEvents
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.recycler.MarkwonAdapter
import io.noties.markwon.utils.NoCopySpannableFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node

/**
 * Block-based Markdown renderer for the large transcript and API trace viewers.
 * Markwon parses the document off the main thread and its RecyclerView adapter
 * renders only the visible blocks, so a one-megabyte trace no longer blocks the
 * first frame while the whole document is turned into one AnnotatedString.
 */
@Composable
internal fun ConductorMarkdownViewer(
    text: String,
    viewerName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val markwon = remember(context, colorScheme) { buildMarkwon(context, colorScheme) }
    val markdownAdapter = remember(colorScheme, viewerName) { buildAdapter(colorScheme, viewerName) }
    val parseState by produceState<ParseState>(ParseState.Loading, text, markwon) {
        value =
            withContext(Dispatchers.Default) {
                runCatching { markwon.parse(text) }
                    .fold({ ParseState.Ready(it) }, { ParseState.Failed(it) })
            }
    }

    LaunchedEffect(Unit) {
        TelemetryEventLog.recordAsync(
            context,
            "conductor_viewer",
            mapOf(
                "viewer" to viewerName.take(40),
                "stage" to "open",
                "chars" to text.length,
            ),
        )
    }
    LaunchedEffect(parseState) {
        when (val state = parseState) {
            is ParseState.Ready ->
                TelemetryEventLog.recordAsync(
                    context,
                    "conductor_viewer",
                    mapOf(
                        "viewer" to viewerName.take(40),
                        "stage" to "ready",
                        "chars" to text.length,
                    ),
                )

            is ParseState.Failed ->
                TelemetryEventLog.recordAsync(
                    context,
                    "conductor_viewer_error",
                    mapOf(
                        "viewer" to viewerName.take(40),
                        "stage" to "parse",
                        "error" to state.error.javaClass.simpleName,
                        "detail" to TelemetryEvents.scrub(state.error.message.orEmpty(), 160),
                        "chars" to text.length,
                    ),
                )

            ParseState.Loading -> Unit
        }
    }

    val ready = parseState as? ParseState.Ready
    LaunchedEffect(ready) {
        if (ready == null) return@LaunchedEffect
        runCatching {
            markdownAdapter.setParsedMarkdown(markwon, ready.node)
            // MarkwonAdapterImpl.setParsedMarkdown only swaps its node list; it
            // does not notify the RecyclerView. Without this the view stays at
            // item count 0 after the initial empty layout: parse finishes, but
            // Rich never shows a block.
            markdownAdapter.notifyDataSetChanged()
            TelemetryEventLog.recordAsync(
                context,
                "conductor_viewer",
                mapOf(
                    "viewer" to viewerName.take(40),
                    "stage" to "adapter",
                    "items" to markdownAdapter.itemCount,
                ),
            )
        }.onFailure { error ->
            TelemetryEventLog.recordAsync(
                context,
                "conductor_viewer_error",
                mapOf(
                    "viewer" to viewerName.take(40),
                    "stage" to "bind",
                    "error" to error.javaClass.simpleName,
                    "detail" to TelemetryEvents.scrub(error.message.orEmpty(), 160),
                ),
            )
        }
    }

    when (val state = parseState) {
        ParseState.Loading ->
            Box(modifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

        is ParseState.Failed ->
            Box(modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.conductor_viewer_render_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

        is ParseState.Ready ->
            AndroidView(
                factory = { ctx ->
                    RecyclerView(ctx).apply {
                        layoutManager = LinearLayoutManager(ctx)
                        itemAnimator = null
                        adapter = markdownAdapter
                        clipToPadding = true
                        val density = resources.displayMetrics.density
                        setPadding(
                            (16 * density).toInt(),
                            (4 * density).toInt(),
                            (16 * density).toInt(),
                            (12 * density).toInt(),
                        )
                    }
                },
                update = { recycler ->
                    if (recycler.adapter !== markdownAdapter) recycler.adapter = markdownAdapter
                },
                modifier = modifier.fillMaxWidth(),
            )
    }
}

private sealed interface ParseState {
    data object Loading : ParseState

    class Ready(
        val node: Node,
    ) : ParseState

    class Failed(
        val error: Throwable,
    ) : ParseState
}

private fun buildMarkwon(
    context: Context,
    colorScheme: ColorScheme,
): Markwon =
    Markwon.builder(context)
        .usePlugin(
            object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .linkColor(colorScheme.primary.toArgb())
                        .blockQuoteColor(colorScheme.outlineVariant.toArgb())
                        .listItemColor(colorScheme.onSurfaceVariant.toArgb())
                        .codeTextColor(colorScheme.onSurface.toArgb())
                        .codeBlockTextColor(colorScheme.onSurface.toArgb())
                        .codeBackgroundColor(colorScheme.surfaceContainerHigh.toArgb())
                        .codeBlockBackgroundColor(colorScheme.surfaceContainerHighest.toArgb())
                        .codeTypeface(Typeface.MONOSPACE)
                        .codeBlockTypeface(Typeface.MONOSPACE)
                        .thematicBreakColor(colorScheme.outlineVariant.toArgb())
                        .headingBreakColor(colorScheme.outlineVariant.toArgb())
                }
            },
        )
        .usePlugin(ConductorInlineCodePlugin(conductorInlineCodeColor(colorScheme).toArgb()))
        .build()

private fun buildAdapter(
    colorScheme: ColorScheme,
    viewerName: String,
): MarkwonAdapter {
    val textColor = colorScheme.onSurface.toArgb()
    val defaultEntry =
        ThemedEntry(
            layoutRes = R.layout.conductor_markdown_block,
            textViewId = 0,
            textColor = textColor,
            textSizeSp = 14f,
            monospace = false,
            viewerName = viewerName,
        )
    val codeEntry =
        ThemedEntry(
            layoutRes = R.layout.conductor_markdown_code_block,
            textViewId = R.id.markdown_code,
            textColor = textColor,
            textSizeSp = 13f,
            monospace = true,
            viewerName = viewerName,
        )
    return MarkwonAdapter.builder(defaultEntry)
        .include(FencedCodeBlock::class.java, codeEntry)
        .include(IndentedCodeBlock::class.java, codeEntry)
        .build()
}

private class ThemedEntry(
    private val layoutRes: Int,
    private val textViewId: Int,
    private val textColor: Int,
    private val textSizeSp: Float,
    private val monospace: Boolean,
    private val viewerName: String,
) : MarkwonAdapter.Entry<Node, ThemedEntry.Holder>() {
    private val cache = HashMap<Node, Spanned>()

    override fun createHolder(
        inflater: LayoutInflater,
        parent: ViewGroup,
    ): Holder = Holder(inflater.inflate(layoutRes, parent, false), textViewId)

    override fun bindHolder(
        markwon: Markwon,
        holder: Holder,
        node: Node,
    ) {
        try {
            val rendered = cache.getOrPut(node) { markwon.render(node) }
            holder.text.setTextColor(textColor)
            holder.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            if (monospace) holder.text.typeface = Typeface.MONOSPACE
            markwon.setParsedMarkdown(holder.text, rendered)
        } catch (error: Throwable) {
            TelemetryEventLog.recordAsync(
                holder.text.context,
                "conductor_viewer_error",
                mapOf(
                    "viewer" to viewerName.take(40),
                    "stage" to "render",
                    "error" to error.javaClass.simpleName,
                    "detail" to TelemetryEvents.scrub(error.message.orEmpty(), 160),
                ),
            )
            holder.text.text = "Could not render this block. Switch to Raw."
        }
    }

    override fun clear() {
        cache.clear()
    }

    class Holder(
        itemView: View,
        textViewId: Int,
    ) : MarkwonAdapter.Holder(itemView) {
        val text: TextView =
            if (textViewId == 0) {
                itemView as TextView
            } else {
                requireView(textViewId)
            }

        init {
            text.setSpannableFactory(NoCopySpannableFactory.getInstance())
        }
    }
}
