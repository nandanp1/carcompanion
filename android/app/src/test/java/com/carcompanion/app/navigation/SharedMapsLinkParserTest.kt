package com.carcompanion.app.navigation

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class SharedMapsLinkParserTest {
    private fun parse(url: String) = SharedMapsLinkParser.parse(URI(url))
    @Test fun extractsShortLinkFromSharedText() {
        assertEquals("maps.app.goo.gl", SharedMapsLinkParser.sharedUrl("Meet here\nhttps://maps.app.goo.gl/example?g_st=ac").host)
    }
    @Test fun destinationCoordinatesWinOverOriginAndCamera() {
        val destination = parse("https://www.google.com/maps/dir/?api=1&origin=10,20&destination=37.5,-122.2")!!
        assertEquals(37.5, destination.latitude!!, 0.00001)
        assertEquals(-122.2, destination.longitude!!, 0.00001)
    }
    @Test fun prefersPlaceIdOverApproximateAddress() {
        assertEquals("ChIJExample", parse("https://www.google.com/maps/search/?api=1&query=Example+Place&query_place_id=ChIJExample")!!.placeId)
    }
    @Test fun readsPlaceCoordinatesRatherThanViewport() {
        val value = parse("https://www.google.com/maps/place/Test+Place/@10,20,15z/data=!4m2!3m1!3d37.5!4d-122.2")!!
        assertEquals(37.5, value.latitude!!, 0.0001)
        assertEquals("Test Place", value.label)
    }
    @Test fun neverNavigatesToCameraCenter() {
        assertNull(parse("https://www.google.com/maps/@10,20,15z"))
    }
    @Test fun addressRemainsUnresolvedForUserConfirmation() {
        assertEquals("123 Example Street", parse("https://maps.google.com/?q=123+Example+Street")!!.address)
    }
    @Test fun directionsPathUsesDestination() {
        assertEquals("Destination", parse("https://www.google.com/maps/dir/Origin/Destination/@10,20,15z/")!!.address)
    }
    @Test fun rejectsUntrustedRedirectTargets() {
        listOf("https://google.com.evil.test/maps/", "https://127.0.0.1/maps/", "https://www.google.com:8443/maps/", "https://user@www.google.com/maps/", "http://maps.google.com/?q=1,2", "https://www.google.com/url?q=bad").forEach {
            assertFalse(it, SharedMapsLinkParser.isAllowed(URI(it)))
        }
    }
    @Test fun rejectsMultiStopRouteRatherThanSilentlyDroppingStops() {
        assertThrows(IllegalArgumentException::class.java) { parse("https://www.google.com/maps/dir/?destination=1,2&waypoints=3,4") }
        assertThrows(IllegalArgumentException::class.java) { parse("https://www.google.com/maps/dir/A/B/C/") }
    }
    @Test fun rejectsInvalidCoordinatesAndAmbiguousLinks() {
        assertThrows(IllegalArgumentException::class.java) { parse("https://maps.google.com/?q=91,181") }
        assertThrows(IllegalArgumentException::class.java) { SharedMapsLinkParser.sharedUrl("https://maps.app.goo.gl/a https://maps.app.goo.gl/b") }
    }
    @Test fun repeatedSameLinkIsNotAnAmbiguousShare() {
        val link = "https://maps.app.goo.gl/example"
        assertEquals(URI(link), SharedMapsLinkParser.sharedUrl("$link\n$link"))
    }
    @Test fun locCoordinatesAreNotSentToAddressGeocoder() {
        val value = parse("https://maps.google.com/?q=loc:37.5,-122.2")!!
        assertEquals(37.5, value.latitude!!, 0.0001)
        assertNull(value.address)
    }
    @Test fun searchPathAddressIsSupported() {
        assertEquals("123 Main Street", parse("https://www.google.com/maps/search/123+Main+Street/")!!.address)
    }
    @Test fun cidQueryIsNotMistakenForAnAddress() {
        assertNull(parse("https://maps.google.com/?q=cid:123456789"))
    }
}
