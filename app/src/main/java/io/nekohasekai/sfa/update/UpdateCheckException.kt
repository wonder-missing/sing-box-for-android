package io.nekohasekai.sfa.update

sealed class UpdateCheckException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    class Unreachable(message: String, cause: Throwable? = null) : UpdateCheckException(message, cause)
}
