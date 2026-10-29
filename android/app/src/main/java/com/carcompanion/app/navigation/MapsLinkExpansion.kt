package com.carcompanion.app.navigation

import java.net.URI
import org.jsoup.Jsoup

/** Pure, bounded link traversal; transport is injected so real failure modes can be tested. */
class MapsLinkExpansion(private val fetch: (URI) -> Response) {
    data class Response(val status: Int, val location: String? = null, val html: String = "")

    fun resolve(start: URI): SharedMapsDestination {
        var uri = start
        val visited = mutableSetOf<URI>()
        repeat(6) {
            require(SharedMapsLinkParser.isAllowed(uri)) { "Unsupported Maps redirect. Paste the destination address below." }
            require(visited.add(uri)) { "Maps returned a redirect loop. Paste the destination address below." }
            SharedMapsLinkParser.parse(uri)?.let { return it }
            val response = fetch(uri)
            uri = when (response.status) {
                301, 302, 303, 307, 308 -> uri.resolve(response.location ?: error("Maps returned an empty redirect."))
                200 -> {
                    val document = Jsoup.parse(response.html)
                    val candidates = document.select("link[rel=canonical][href], meta[property=og:url][content]")
                        .mapNotNull { element ->
                            val value = if (element.tagName() == "link") element.attr("href") else element.attr("content")
                            runCatching { uri.resolve(value) }.getOrNull()
                        }.filter { it != uri && SharedMapsLinkParser.isAllowed(it) }.distinct()
                    require(candidates.size == 1) { "Maps did not provide a destination link. Paste the destination address below." }
                    candidates.single()
                }
                else -> error("Maps link lookup failed (HTTP ${response.status}). Retry or enter the destination address below.")
            }
        }
        error("Too many Maps redirects. Paste the destination address below.")
    }
}
