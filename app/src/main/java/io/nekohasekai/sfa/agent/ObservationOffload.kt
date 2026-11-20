package io.nekohasekai.sfa.agent

data class ObservationOffload(
    val path: String,
    val bytes: Long,
)

/**
 * Persists oversized tool observations outside the model transcript. The
 * model receives the returned path plus a short preview; the full JSON stays
 * available to file tools and the VFS.
 */
fun interface ObservationOffloadStore {
    suspend fun save(
        tool: String,
        json: String,
    ): ObservationOffload?
}
