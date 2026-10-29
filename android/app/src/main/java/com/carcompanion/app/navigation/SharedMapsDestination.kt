package com.carcompanion.app.navigation

import java.net.URI
import java.net.URLDecoder

/** A destination, never the viewport center or a promise to reproduce Maps' selected route. */
data class SharedMapsDestination(
    val label: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val placeId: String? = null,
    val address: String? = null,
)

object SharedMapsLinkParser {
    private val coordinates = Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*$")
    fun sharedUrl(text: String): URI {
        require(text.length <= 16_384) { "The shared text is too long." }
        val urls = Regex("https?://[^\\s<>]+").findAll(text).mapNotNull {
            runCatching { URI(it.value) }.getOrNull()?.takeIf(::isAllowed)
        }.distinct().toList()
        require(urls.size == 1) { "Share one destination link from Google Maps." }
        return urls.single()
    }

    fun isAllowed(uri: URI): Boolean {
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return false
        return when (uri.host?.lowercase()) {
            "maps.app.goo.gl" -> true
            "goo.gl" -> uri.path.startsWith("/maps/")
            "maps.google.com" -> true
            "www.google.com", "google.com" -> uri.path == "/maps" || uri.path.startsWith("/maps/")
            else -> false
        }
    }

    fun parse(uri: URI): SharedMapsDestination? {
        require(isAllowed(uri)) { "This is not a supported Google Maps link." }
        val query = uri.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('='))
        }
        require(query["waypoints"].isNullOrBlank()) { "Share the destination itself; multi-stop routes are not supported yet." }
        val target = query["destination"] ?: query["daddr"] ?: query["query"] ?: query["q"]
        val placeId = query["destination_place_id"] ?: query["query_place_id"]
            ?: target?.takeIf { it.startsWith("place_id:") }?.removePrefix("place_id:")
        if (!placeId.isNullOrBlank()) return SharedMapsDestination(
            label = target?.takeUnless { it.startsWith("place_id:") }?.take(200) ?: "Shared Google Maps place",
            placeId = placeId.takeIf { it.length <= 256 } ?: error("Invalid place ID."))
        if (!target.isNullOrBlank() && !target.startsWith("cid:") && !target.startsWith("0x")) return target(target.removePrefix("loc:"))
        val path = decode(uri.rawPath.orEmpty())
        if (path.startsWith("/maps/dir/")) {
            val parts = path.removePrefix("/maps/dir/").split('/').takeWhile { !it.startsWith('@') && !it.startsWith("data=") }.filter { it.isNotBlank() }
            require(parts.size <= 2) { "Share the destination itself; multi-stop routes are not supported yet." }
            return parts.lastOrNull()?.let(::target)
        }
        if (path.startsWith("/maps/search/")) {
            val name = path.removePrefix("/maps/search/").substringBefore('/')
            if (name.isNotBlank()) return target(name)
        }
        if (path.startsWith("/maps/place/")) {
            val name = path.removePrefix("/maps/place/").substringBefore('/').take(200)
            val points = Regex("!3d(-?\\d+(?:\\.\\d+)?)!4d(-?\\d+(?:\\.\\d+)?)").findAll(uri.toString()).toList()
            if (points.size == 1) return coordinateTarget(points[0].groupValues[1], points[0].groupValues[2], name)
            if (name.isNotBlank()) return target(name)
        }
        // @lat,lng is a map camera position; it must never become a destination.
        return null
    }

    private fun target(value: String): SharedMapsDestination {
        require(value.length in 1..500) { "Invalid shared destination." }
        val match = coordinates.matchEntire(value)
        return if (match != null) coordinateTarget(match.groupValues[1], match.groupValues[2], value)
        else SharedMapsDestination(value.take(200), address = value)
    }
    private fun coordinateTarget(lat: String, lng: String, label: String): SharedMapsDestination {
        val latitude = lat.toDouble(); val longitude = lng.toDouble()
        require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Invalid destination coordinates." }
        return SharedMapsDestination(label, latitude, longitude)
    }
    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
}
