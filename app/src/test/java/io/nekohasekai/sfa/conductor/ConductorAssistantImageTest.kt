package io.nekohasekai.sfa.conductor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorAssistantImageTest {
    @Test
    fun `splits a markdown image into a text and image segment`() {
        val segments =
            assistantMarkdownSegments(
                "before ![diagram](https://example.com/diagram.png) after",
            )

        assertEquals(3, segments.size)
        assertEquals("before ", (segments[0] as ConductorAssistantSegment.Text).text)
        assertEquals(
            ConductorAssistantSegment.Image(
                alt = "diagram",
                url = "https://example.com/diagram.png",
            ),
            segments[1],
        )
        assertEquals(" after", (segments[2] as ConductorAssistantSegment.Text).text)
    }

    @Test
    fun `keeps markdown image syntax inside inline code`() {
        val segments = assistantMarkdownSegments("Use `![x](https://example.com/x.png)` literally.")

        assertTrue(segments.single() is ConductorAssistantSegment.Text)
        assertEquals(
            "Use `![x](https://example.com/x.png)` literally.",
            (segments.single() as ConductorAssistantSegment.Text).text,
        )
    }

    @Test
    fun `accepts embedded image data`() {
        val segments = assistantMarkdownSegments("![dot](data:image/png;base64,AAAA)")

        assertEquals(
            ConductorAssistantSegment.Image(
                alt = "dot",
                url = "data:image/png;base64,AAAA",
            ),
            segments.single(),
        )
    }
}
