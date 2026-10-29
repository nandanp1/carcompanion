package com.carcompanion.app.navigation

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.util.Locale

class SharedMapsResolver(private val context: Context) {
    suspend fun resolve(text: String, allowAddress: Boolean = false): List<SharedMapsDestination> = withTimeout(35_000) {
        withContext(Dispatchers.IO) {
            val lookupContext = coroutineContext
            val resolved = if (allowAddress && !text.contains(Regex("https?://", RegexOption.IGNORE_CASE))) {
                val address = text.trim()
                require(address.length in 1..500) { "Enter a destination address (up to 500 characters)." }
                SharedMapsDestination(address, address = address)
            } else MapsLinkExpansion { uri ->
                lookupContext.ensureActive()
                val connection = uri.toURL().openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; CarCompanion/0.3.1)")
                    val code = connection.responseCode
                    val html = if (code == 200) connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val result = StringBuilder()
                        val buffer = CharArray(4096)
                        while (true) {
                            lookupContext.ensureActive()
                            val count = reader.read(buffer)
                            if (count < 0) break
                            require(result.length + count <= 1_048_576) { "Maps response is too large. Enter the destination address below." }
                            result.append(buffer, 0, count)
                        }
                        result.toString()
                    } else ""
                    MapsLinkExpansion.Response(code, connection.getHeaderField("Location"), html)
                } finally { connection.disconnect() }
            }.resolve(SharedMapsLinkParser.sharedUrl(text))
            if (resolved.address == null) return@withContext listOf(resolved)
            require(Geocoder.isPresent()) { "Address lookup is unavailable on this phone. Share a pinned location from Maps." }
            @Suppress("DEPRECATION")
            val matches = Geocoder(context, Locale.getDefault()).getFromLocationName(resolved.address, 5).orEmpty()
            require(matches.isNotEmpty()) { "Address not found. Share a pinned location from Maps instead." }
            matches.filter { it.hasLatitude() && it.hasLongitude() }.map {
                SharedMapsDestination(it.getAddressLine(0) ?: resolved.label, it.latitude, it.longitude)
            }.distinctBy { it.latitude to it.longitude }.also {
                require(it.isNotEmpty()) { "No destination coordinates found." }
            }
        }
    }
}
