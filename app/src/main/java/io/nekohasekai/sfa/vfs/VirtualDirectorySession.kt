package io.nekohasekai.sfa.vfs

import kotlinx.coroutines.CancellationException

data class VfsReply(
    val text: String,
    val bytesRead: Int = 0,
    val errorCode: String? = null,
)

/** Turns one engine request into one protocol response. Stateless per call. */
class VirtualDirectorySession(
    private val source: VirtualDirectorySource,
) {
    suspend fun respond(message: VfsIncoming): VfsReply? =
        when (message) {
            is VfsIncoming.ServerHello -> null
            is VfsIncoming.Ping -> null
            is VfsIncoming.Stat ->
                replyText(message.id) {
                    VfsProtocol.entry(message.id, source.stat(message.path))
                }

            is VfsIncoming.List ->
                replyText(message.id) {
                    VfsProtocol.entries(message.id, source.list(message.path))
                }

            is VfsIncoming.Read -> respondRead(message)
        }

    private suspend fun respondRead(message: VfsIncoming.Read): VfsReply {
        if (message.offset < 0 || message.length < 0 || message.length > VfsProtocol.MAX_READ_BYTES) {
            return error(message.id, VfsErrorCode.BAD_REQUEST, "Invalid read range.")
        }
        return reply(message.id) {
            val result = source.read(message.path, message.offset, message.length)
            if (result.data.isEmpty() && !result.eof && message.length > 0) {
                throw VfsException(
                    VfsErrorCode.PROVIDER_ERROR,
                    "The provider returned an empty chunk before EOF.",
                )
            }
            if (result.data.size > message.length) {
                throw VfsException(
                    VfsErrorCode.PROVIDER_ERROR,
                    "The provider returned more data than requested.",
                )
            }
            VfsReply(
                text = VfsProtocol.data(message.id, result.data, result.eof),
                bytesRead = result.data.size,
            )
        }
    }

    private suspend fun reply(
        id: Long,
        block: suspend () -> VfsReply,
    ): VfsReply =
        try {
            block()
        } catch (failure: VfsException) {
            error(id, failure.code, failure.message)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            error(
                id,
                VfsErrorCode.PROVIDER_ERROR,
                "The provider could not complete this request.",
            )
        }

    private suspend fun replyText(
        id: Long,
        block: suspend () -> String,
    ): VfsReply = reply(id) { VfsReply(block()) }

    private fun error(
        id: Long,
        code: String,
        message: String?,
    ): VfsReply = VfsReply(VfsProtocol.error(id, code, message), errorCode = code)
}
