package com.carcompanion.app.ble

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class ProtocolTest {
    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test fun canonicalCrc32() { assertEquals(0xcbf43926L, Protocol.crc32("123456789".toByteArray())) }

    @Test fun crossLanguageGoldenVectors() {
        val vectors = JSONObject(File("../../protocol/message-definitions/golden-vectors.json").readText()).getJSONArray("messages")
        for (index in 0 until vectors.length()) {
            val message = vectors.getJSONObject(index)
            val payload = message.getString("payload_hex").hex()
            val frames = message.getJSONArray("frames_hex")
            var offset = 0
            for (frameIndex in 0 until frames.length()) {
                val encoded = Protocol.frame(payload, message.getInt("id"), offset, message.getInt("mtu"))
                assertEquals(message.getString("name"), frames.getString(frameIndex), encoded.hex())
                val decoded = Protocol.decodeFrame(encoded)
                assertEquals(offset, decoded.offset)
                offset += decoded.payload.size
            }
            assertEquals(payload.size, offset)
        }
    }

    @Test fun allMtuPayloadsRoundTripAtMaximumImageSize() {
        val bytes = ByteArray(32768) { (it % 251).toByte() }
        for (mtu in listOf(23, 64, 185, 247, 517)) {
            var offset = 0
            val rebuilt = ArrayList<Byte>()
            while (offset < bytes.size) {
                val frame = Protocol.frame(bytes, 65535, offset, mtu)
                assertTrue(frame.size <= minOf(mtu - 3, 512))
                val decoded = Protocol.decodeFrame(frame)
                assertEquals(bytes.size, decoded.total)
                rebuilt.addAll(decoded.payload.toList())
                offset += decoded.payload.size
            }
            assertArrayEquals(bytes, rebuilt.toByteArray())
        }
    }

    @Test fun rejectsMalformedFrames() {
        val good = Protocol.frame(ByteArray(40), 1, 0, 23)
        for (bad in listOf(good.copyOf(12), good.clone().also { it[0] = 2 }, good.clone().also { it[1] = 7 },
            good.clone().also { it[2] = 0 }, good.clone().also { it[4] = 127 }, ByteArray(513))) {
            assertThrows(IllegalArgumentException::class.java) { Protocol.decodeFrame(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { Protocol.frame(byteArrayOf(1), 1, 0, 22) }
        assertThrows(IllegalArgumentException::class.java) { Protocol.frame(byteArrayOf(1), 1, 0, 518) }
    }

    @Test fun controlStatusLittleEndianAndVersionChecked() {
        val status = Protocol.status("010201002a002000785634122639f4cb".hex())
        assertEquals(42, status.id)
        assertEquals(0x12345678L, status.offset)
        assertEquals(0xcbf43926L, status.cachedCrc)
        assertThrows(IllegalArgumentException::class.java) { Protocol.status(ByteArray(15)) }
        assertThrows(IllegalArgumentException::class.java) { Protocol.status(ByteArray(16)) }
    }

    @Test fun reconnectBackoffIsCapped() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), (0..6).map(Protocol::retryDelay))
    }

    @Test fun interruptedTransferResumesAtAcknowledgedOffset() = runTest {
        val bytes = ByteArray(1600) { it.toByte() }
        var received = 0
        var begins = 0
        var interrupted = false
        val result = ArrayList<Byte>()
        ArtworkTransfer({ 247 }, begin = { id, _, _ -> begins++; Protocol.Status(2, 1, id, 32, received.toLong(), 0) },
            write = { packet ->
                val frame = Protocol.decodeFrame(packet)
                assertEquals(received, frame.offset)
                result.addAll(frame.payload.toList())
                received += frame.payload.size
                if (!interrupted) { interrupted = true; throw IOException("ATT response lost after receiver accepted bytes") }
            }, commit = { id -> Protocol.Status(2, 2, id, 32, received.toLong(), Protocol.crc32(result.toByteArray())) }
        ).send(bytes, 42)
        assertEquals(2, begins)
        assertArrayEquals(bytes, result.toByteArray())
    }

    @Test fun reconnectWithEmptyReceiverStartsFresh() = runTest {
        val bytes = ByteArray(1200) { it.toByte() }
        var received = 0
        var interrupted = false
        val offsets = mutableListOf<Int>()
        ArtworkTransfer({ 23 }, begin = { id, _, _ -> Protocol.Status(2, 1, id, 32, received.toLong(), 0) },
            write = { packet ->
                val frame = Protocol.decodeFrame(packet)
                offsets += frame.offset
                if (!interrupted) { interrupted = true; received = 0; throw IOException("Disconnected; peripheral discarded partial image") }
                received += frame.payload.size
            }, commit = { id -> Protocol.Status(2, 2, id, 32, received.toLong(), Protocol.crc32(bytes)) }
        ).send(bytes, 12)
        assertEquals(listOf(0, 0), offsets.take(2))
        assertEquals(bytes.size, received)
    }

    @Test fun cachedImageAvoidsAllWrites() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        ArtworkTransfer({ 23 }, { id, _, crc -> Protocol.Status(2, 3, id, 32, 0, crc) },
            { fail("Cached image must not be retransferred") }, { error("Cached image must not be committed") }).send(bytes, 3)
    }

    @Test fun wrongCacheCrcExhaustsThreeAttempts() = runTest {
        var attempts = 0
        try {
            ArtworkTransfer({ 23 }, { id, _, _ -> attempts++; Protocol.Status(2, 3, id, 32, 0, 123) },
                { fail("Invalid cache must not write") }, { error("Invalid cache must not commit") }).send(byteArrayOf(1, 2), 3)
            fail("Must reject incorrect cache CRC")
        } catch (_: IllegalStateException) { assertEquals(3, attempts) }
    }

    @Test fun badCrcRetriesFromBegin() = runTest {
        val bytes = ByteArray(500)
        var commits = 0
        var starts = 0
        ArtworkTransfer({ 247 }, { id, _, _ -> starts++; Protocol.Status(2, 1, id, 32, 0, 0) },
            {}, { id -> commits++; Protocol.Status(2, if (commits == 1) 5 else 2, id, 32, 0, Protocol.crc32(bytes)) }).send(bytes, 1)
        assertEquals(2, starts)
        assertEquals(2, commits)
    }

    @Test fun rejectsOversizedArtworkBeforeBegin() = runTest {
        try {
            ArtworkTransfer({ 247 }, { _, _, _ -> error("Must reject before allocation") }, {}, { error("Must reject") }).send(ByteArray(32769), 1)
            fail("Oversized payload accepted")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
