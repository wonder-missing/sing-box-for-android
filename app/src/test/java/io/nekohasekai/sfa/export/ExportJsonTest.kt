package io.nekohasekai.sfa.export

import io.nekohasekai.sfa.activity.ActivityEvent
import io.nekohasekai.sfa.activity.ActivityLane
import io.nekohasekai.sfa.activity.ActivityTypes
import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.compose.model.GroupItem
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportJsonTest {
    @Test
    fun `groups export contains delays and no url`() {
        val group =
            Group(
                tag = "Google",
                type = "urltest",
                displayType = "URLTest",
                selectable = false,
                selected = "node-a",
                isExpand = true,
                items =
                listOf(
                    GroupItem(
                        tag = "node-a",
                        type = "hysteria2",
                        displayType = "Hysteria2",
                        urlTestTime = 123,
                        urlTestDelay = 45,
                    ),
                ),
            )

        val text = ExportJson.groups(1_000, "app", "device", "rev123", listOf(group))
        val root = Json.parseToJsonElement(text)
        assertTrue(text.contains("\"kind\":\"groups\""))
        assertTrue(text.contains("\"tag\":\"Google\""))
        assertTrue(text.contains("\"delay_ms\":45"))
        assertTrue(text.contains("\"profile_rev\":\"rev123\""))
        assertFalse(text.contains("http://"))
        assertFalse(text.contains("https://"))
        assertTrue(root.toString().isNotEmpty())
    }

    @Test
    fun `activity export serializes events`() {
        val event =
            ActivityEvent(
                ts = 100,
                lane = ActivityLane.SERVICE,
                type = ActivityTypes.DNS_CACHE_MISS,
                subject = "fakeip",
                count = 7,
            )

        val text = ExportJson.activity(1_000, "app", "device", null, 0, 1_000, listOf(event))
        assertTrue(text.contains("\"kind\":\"activity\""))
        assertTrue(text.contains("\"type\":\"dns_cache_miss\""))
        assertTrue(text.contains("\"count\":7"))
        assertFalse(text.contains("\"profile_rev\""))
    }
}
