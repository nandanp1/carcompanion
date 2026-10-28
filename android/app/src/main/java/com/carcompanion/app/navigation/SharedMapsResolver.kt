package com.carcompanion.app.navigation

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

class SharedMapsResolver(private val context: Context) {
    suspend fun resolve(text: String): List<SharedMapsDestination> = withTimeout(35_000) {
        withContext(Dispatchers.IO) {
            var uri = SharedMapsLinkParser.sharedUrl(text)
            var destination = SharedMapsLinkParser.parse(uri)
            var redirects = 0
            while (destination == null && redirects++ < 5) {
                require(SharedMapsLinkParser.isAllowed(uri)) { "Unsupported Maps redirect." }
                val connection = uri.toURL().openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    connection.setRequestProperty("User-Agent", "CarCompanion/0.2")
                    val code = connection.responseCode
                    require(code in listOf(301, 302, 303, 307, 308)) {
                        "This Maps link could not be resolved. Share the place itself, or a Maps link with its address."
                    }
                    val next = connection.getHeaderField("Location") ?: error("Maps returned an empty redirect.")
                    uri = uri.resolve(URI(next))
                    require(SharedMapsLinkParser.isAllowed(uri)) { "Unsupported Maps redirect." }
                    destination = SharedMapsLinkParser.parse(uri)
                } finally { connection.disconnect() }
            }
            val resolved = destination ?: error("Too many Maps redirects. Share the destination itself.")
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
