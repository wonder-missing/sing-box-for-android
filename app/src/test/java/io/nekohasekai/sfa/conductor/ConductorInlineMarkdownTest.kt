package io.nekohasekai.sfa.conductor

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `inline code accent stays readable on light and dark surfaces`() {
        val dark =
            darkColorScheme(
                tertiary = Color(0xFF3498DB),
                onSurface = Color(0xFFE6E0E9),
                surface = Color(0xFF141218),
            )
        val light =
            lightColorScheme(
                tertiary = Color(0xFF3498DB),
                onSurface = Color(0xFF1D1B20),
                surface = Color(0xFFFFFBFE),
            )

        assertTrue(contrastRatio(conductorInlineCodeColor(dark), dark.surface) >= 4.5f)
        assertTrue(contrastRatio(conductorInlineCodeColor(light), light.surface) >= 4.5f)
    }

    private fun contrastRatio(
        foreground: Color,
        background: Color,
    ): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
