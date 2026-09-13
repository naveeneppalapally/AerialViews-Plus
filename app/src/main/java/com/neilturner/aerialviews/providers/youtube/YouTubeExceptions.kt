package com.neilturner.aerialviews.providers.youtube

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class YouTubeExtractionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class YouTubeSourceException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * YouTube's anonymous-access bot gate is up: searches may still work but no
 * extraction will succeed until the cooldown lapses. Callers must surface
 * this distinctly (rate-limited, cached content kept) instead of spinning
 * through a doomed refresh or reporting a generic failure.
 */
class YouTubeBotBlockedException(
    val cooldownMinutes: Long,
) : Exception("YouTube is temporarily rate-limiting this network; retry in ~$cooldownMinutes min")

fun Throwable.isNetworkError(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (
            current is UnknownHostException ||
            current is SocketTimeoutException ||
            current is ConnectException ||
            current is SocketException ||
            current is SSLException ||
            current is IOException
        ) {
            return true
        }
        current = current.cause
    }
    return false
}
