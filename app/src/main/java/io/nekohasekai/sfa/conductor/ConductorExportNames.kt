package io.nekohasekai.sfa.conductor

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Suggested names for the Conductor save sheet. The stamp is the device's
 * local time, so two uploads of the same conversation do not share a name.
 */
object ConductorExportNames {
    fun conversation(
        at: Date = Date(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String = named("conductor-conversation", at, zone)

    fun apiTrace(
        at: Date = Date(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String = named("conductor-api-trace", at, zone)

    private fun named(
        stem: String,
        at: Date,
        zone: TimeZone,
    ): String {
        val stamp =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
                timeZone = zone
            }.format(at)
        return "$stem-$stamp.md"
    }
}
