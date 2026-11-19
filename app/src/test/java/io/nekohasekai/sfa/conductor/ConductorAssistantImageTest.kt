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
    fun `extracts a fenced svg block`() {
        val segments =
            assistantMarkdownSegments(
                "before\n```svg\n<svg viewBox=\"0 0 10 10\"><circle cx=\"5\" cy=\"5\" r=\"4\"/></svg>\n```\nafter",
            )

        assertEquals(3, segments.size)
        assertEquals("before\n", (segments[0] as ConductorAssistantSegment.Text).text)
        assertEquals(
            "<svg viewBox=\"0 0 10 10\"><circle cx=\"5\" cy=\"5\" r=\"4\"/></svg>",
            (segments[1] as ConductorAssistantSegment.Svg).source,
        )
        assertEquals("\nafter", (segments[2] as ConductorAssistantSegment.Text).text)
    }

    @Test
    fun `extracts a raw svg block`() {
        val segments = assistantMarkdownSegments("<svg><rect width=\"1\" height=\"1\"/></svg>")

        assertTrue(segments.single() is ConductorAssistantSegment.Svg)
    }

    @Test
    fun `accepts a local resource path`() {
        val segment = assistantMarkdownSegments("![icon](conductor/resources/icon.svg)").single()

        assertEquals(
            ConductorAssistantSegment.Image(
                alt = "icon",
                url = "conductor/resources/icon.svg",
            ),
            segment,
        )
    }

    @Test
    fun `extracts a fenced code block`() {
        val segments =
            assistantMarkdownSegments(
                "before\n```kotlin\nval answer = 42\n```\nafter",
            )

        assertEquals(3, segments.size)
        assertEquals("before\n", (segments[0] as ConductorAssistantSegment.Text).text)
        assertEquals(
            ConductorAssistantSegment.Code(language = "kotlin", code = "val answer = 42"),
            segments[1],
        )
        assertEquals("\nafter", (segments[2] as ConductorAssistantSegment.Text).text)
    }

    @Test
    fun `extracts a markdown table outside code fences`() {
        val segments =
            assistantMarkdownSegments(
                "before\n| Step | Tool |\n| --- | --- |\n| 1 | job_run |\n| 2 | job_file |\nafter",
            )

        assertEquals(3, segments.size)
        assertEquals("before\n", (segments[0] as ConductorAssistantSegment.Text).text)
        assertEquals(
            ConductorAssistantSegment.Table(
                header = listOf("Step", "Tool"),
                rows = listOf(listOf("1", "job_run"), listOf("2", "job_file")),
            ),
            segments[1],
        )
        assertEquals("\nafter", (segments[2] as ConductorAssistantSegment.Text).text)
    }

    @Test
    fun `does not parse table syntax inside a code fence`() {
        val segments =
            assistantMarkdownSegments(
                "```text\n| Step | Tool |\n| --- | --- |\n```",
            )

        assertEquals(
            ConductorAssistantSegment.Code(
                language = "text",
                code = "| Step | Tool |\n| --- | --- |",
            ),
            segments.single(),
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
