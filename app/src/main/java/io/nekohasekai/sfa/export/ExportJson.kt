package io.nekohasekai.sfa.export

import io.nekohasekai.sfa.activity.ActivityEvent
import io.nekohasekai.sfa.compose.model.Group
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manual export formats. They contain only the data already visible in the
 * app: group tags/delays, activity events and a profile revision hash. No
 * subscription URL, token or raw domain is included.
 */
internal object ExportJson {
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = false
        }

    fun groups(
        exportedAt: Long,
        appVersion: String,
        deviceId: String,
        profileRev: String?,
        groups: List<Group>,
    ): String =
        json.encodeToString(
            GroupsExport(
                exported_at = exportedAt,
                app_version = appVersion,
                device_id = deviceId,
                profile_rev = profileRev,
                groups = groups.map { group ->
                    GroupExport(
                        tag = group.tag,
                        type = group.type,
                        selected = group.selected,
                        selectable = group.selectable,
                        items = group.items.map { item ->
                            GroupItemExport(
                                tag = item.tag,
                                type = item.type,
                                delay_ms = item.urlTestDelay,
                                tested_at = item.urlTestTime,
                            )
                        },
                    )
                },
            ),
        )

    fun activity(
        exportedAt: Long,
        appVersion: String,
        deviceId: String,
        profileRev: String?,
        windowStart: Long,
        windowEnd: Long,
        events: List<ActivityEvent>,
    ): String =
        json.encodeToString(
            ActivityExport(
                exported_at = exportedAt,
                app_version = appVersion,
                device_id = deviceId,
                profile_rev = profileRev,
                window_start = windowStart,
                window_end = windowEnd,
                events = events,
            ),
        )
}

@Serializable
private data class GroupsExport(
    val kind: String = "groups",
    val exported_at: Long,
    val app_version: String,
    val device_id: String,
    val profile_rev: String? = null,
    val groups: List<GroupExport>,
)

@Serializable
private data class GroupExport(
    val tag: String,
    val type: String,
    val selected: String,
    val selectable: Boolean,
    val items: List<GroupItemExport>,
)

@Serializable
private data class GroupItemExport(
    val tag: String,
    val type: String,
    val delay_ms: Int,
    val tested_at: Long,
)

@Serializable
private data class ActivityExport(
    val kind: String = "activity",
    val exported_at: Long,
    val app_version: String,
    val device_id: String,
    val profile_rev: String? = null,
    val window_start: Long,
    val window_end: Long,
    val events: List<ActivityEvent>,
)
