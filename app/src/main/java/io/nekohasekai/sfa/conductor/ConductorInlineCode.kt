package io.nekohasekai.sfa.conductor

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.SpanFactory
import org.commonmark.node.Code

/**
 * One inline-code style for the message stream and the transcript viewer.
 * Markwon's default CodeSpan adds a filled capsule and shrinks the text; this
 * span keeps the surrounding size and emphasis, then applies only the theme
 * accent, monospace typeface and a transparent background.
 */
internal class ConductorInlineCodeSpan(
    private val color: Int,
) : MetricAffectingSpan() {
    override fun updateMeasureState(paint: TextPaint) {
        paint.typeface = Typeface.create(Typeface.MONOSPACE, paint.typeface?.style ?: Typeface.NORMAL)
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.typeface = Typeface.create(Typeface.MONOSPACE, ds.typeface?.style ?: Typeface.NORMAL)
        ds.color = color
        ds.bgColor = Color.TRANSPARENT
    }
}

/** Replaces Markwon's CodeSpan in the block-based transcript viewer. */
internal class ConductorInlineCodePlugin(
    private val color: Int,
) : AbstractMarkwonPlugin() {
    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
        builder.setFactory(
            Code::class.java,
            SpanFactory { _, _ -> ConductorInlineCodeSpan(color) },
        )
    }
}
