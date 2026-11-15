package io.nekohasekai.sfa.update

/** Release notes for the installed WonderSing build. */
data class ReleaseNotes(
    val version: String,
    val tag: String,
    val notes: String,
    val releaseUrl: String,
)
