package io.nekohasekai.sfa.conductor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class ConductorInlineMarkdownTest {
    @Test
    fun `styles inline code and bold and removes their markers`() {
        val codeBackground = Color(0xFF203040)
        val codeColor = Color(0xFFE0F0FF)
        val rendered =
            markdownAnnotated(
                "Run `tool_name` then **confirm** and `~/path`.",
                codeBackground,
                codeColor,
            )

        assertEquals("Run tool_name then confirm and ~/path.", rendered.text)
        val codeSpan = rendered.spanStyles.first { it.start == rendered.text.indexOf("tool_name") }
        assertEquals(FontFamily.Monospace, codeSpan.item.fontFamily)
        assertEquals(codeBackground, codeSpan.item.background)
        assertEquals(codeColor, codeSpan.item.color)
        val boldSpan = rendered.spanStyles.first { it.start == rendered.text.indexOf("confirm") }
        assertEquals(FontWeight.SemiBold, boldSpan.item.fontWeight)
    }

    @Test
    fun `leaves unmatched markers as literal text`() {
        val rendered = markdownAnnotated("a `open and **bold", Color.White, Color.Black)

        assertEquals("a `open and **bold", rendered.text)
    }
}
