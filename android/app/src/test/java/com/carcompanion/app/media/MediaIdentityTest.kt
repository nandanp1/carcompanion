package com.carcompanion.app.media

import org.junit.Assert.*
import org.junit.Test

class MediaIdentityTest {
    @Test fun metadataChangesInvalidateMissingOrReusedMediaId() {
        val before = MediaIdentity.trackId(null, "Track A", "Artist", "Album")
        assertNotEquals(before, MediaIdentity.trackId(null, "Track B", "Artist", "Album"))
        assertNotEquals(MediaIdentity.trackId("reused", "Track A", "Artist", "Album"),
            MediaIdentity.trackId("reused", "Track A", "Artist", "Different album"))
    }
    @Test fun identicalMetadataKeepsCacheIdentity() {
        assertEquals(MediaIdentity.trackId("id", "Track", "Artist", "Album"), MediaIdentity.trackId("id", "Track", "Artist", "Album"))
    }
    @Test fun progressUsesMonotonicTimeAndClampsAtEnd() {
        assertEquals(1500, MediaIdentity.position(500, 5000, true, 1f, 1000, 2000))
        assertEquals(5000, MediaIdentity.position(4500, 5000, true, 1f, 1000, 2000))
    }
    @Test fun missingPlaybackTimestampDoesNotJumpToTrackEnd() {
        assertEquals(500, MediaIdentity.position(500, 5000, true, 1f, 0, 200000))
    }
    @Test fun pausedAndBackwardTimestampDoNotAdvance() {
        assertEquals(500, MediaIdentity.position(500, 5000, false, 1f, 1000, 2000))
        assertEquals(500, MediaIdentity.position(500, 5000, true, 1f, 1000, 500))
    }
}
