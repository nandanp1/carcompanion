package com.carcompanion.app.media

import java.security.MessageDigest

object MediaIdentity {
    /** Include the visible fields: some sessions reuse or omit their media ID. */
    fun trackId(mediaId: String?, title: String, artist: String, album: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(listOf(mediaId.orEmpty(), title, artist, album).joinToString("\u0000").toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun position(baseMs: Long, durationMs: Long, playing: Boolean, speed: Float,
                 updateElapsedMs: Long, nowElapsedMs: Long): Long {
        val elapsed = if (updateElapsedMs > 0) (nowElapsedMs - updateElapsedMs).coerceAtLeast(0) else 0L
        val advanced = if (playing && speed.isFinite() && speed > 0f) baseMs + (elapsed * speed).toLong() else baseMs
        return advanced.coerceIn(0, if (durationMs > 0) durationMs else 86_400_000L)
    }
}
