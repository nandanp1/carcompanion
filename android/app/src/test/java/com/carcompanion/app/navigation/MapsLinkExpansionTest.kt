package com.carcompanion.app.navigation

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class MapsLinkExpansionTest {
    private val short = URI("https://maps.app.goo.gl/example")
    @Test fun followsShortLinkRedirect() {
        val result = MapsLinkExpansion { MapsLinkExpansion.Response(302, "https://maps.google.com/?q=37.5,-122.2") }.resolve(short)
        assertEquals(37.5, result.latitude!!, 0.0001)
    }
    @Test fun resolvesHtmlLandingPageInsteadOfRejectingHttp200() {
        val result = MapsLinkExpansion { MapsLinkExpansion.Response(200, html = """
            <link rel="canonical" href="https://www.google.com/maps/search/?api=1&amp;query=Test&amp;query_place_id=ChIJExample">
            <meta property="og:url" content="https://www.google.com/maps/search/?api=1&amp;query=Test&amp;query_place_id=ChIJExample">
        """) }.resolve(short)
        assertEquals("ChIJExample", result.placeId)
    }
    @Test fun cannotFollowExternalCanonicalOrRedirect() {
        for (reply in listOf(MapsLinkExpansion.Response(302, "https://evil.test/maps"),
            MapsLinkExpansion.Response(200, html = "<link rel='canonical' href='https://evil.test/maps'>"))) {
            var calls = 0
            assertThrows(IllegalArgumentException::class.java) { MapsLinkExpansion { calls++; reply }.resolve(short) }
            assertEquals(1, calls)
        }
    }
    @Test fun detectsRedirectLoops() {
        var calls = 0
        assertThrows(IllegalArgumentException::class.java) {
            MapsLinkExpansion { calls++; MapsLinkExpansion.Response(302, short.toString()) }.resolve(short)
        }
        assertEquals(1, calls)
    }
    @Test fun doesNotGuessFromPageTitleOrCamera() {
        assertThrows(IllegalArgumentException::class.java) {
            MapsLinkExpansion { MapsLinkExpansion.Response(200, html = "<title>Coffee shop</title>") }.resolve(short)
        }
    }
    @Test fun reportsHttpFailure() {
        val error = assertThrows(IllegalStateException::class.java) {
            MapsLinkExpansion { MapsLinkExpansion.Response(503) }.resolve(short)
        }
        assertTrue(error.message!!.contains("503"))
    }
    @Test fun directDestinationNeedsNoNetwork() {
        val result = MapsLinkExpansion { error("Unexpected network request") }.resolve(URI("https://maps.google.com/?q=1,2"))
        assertEquals(2.0, result.longitude!!, 0.0)
    }
}
