package com.carcompanion.app.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

object Protocol {
    const val VERSION = 1
    const val HEADER_SIZE = 12
    const val MAX_ARTWORK = 32768
    private const val SUFFIX = "-5a7e-4b7d-9c2a-7b986c0a0000"
    val SERVICE: UUID = UUID.fromString("98600000$SUFFIX")
    val CONTROL: UUID = UUID.fromString("98600001$SUFFIX")
    val NAVIGATION: UUID = UUID.fromString("98600002$SUFFIX")
    val DRIVING: UUID = UUID.fromString("98600003$SUFFIX")
    val MUSIC: UUID = UUID.fromString("98600004$SUFFIX")
    val ARTWORK: UUID = UUID.fromString("98600005$SUFFIX")
    val PLAYBACK: UUID = UUID.fromString("98600006$SUFFIX")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
    fun capacity(mtu: Int): Int {
        require(mtu in 23..517)
        return (mtu - 3).coerceAtMost(512) - HEADER_SIZE
    }
    fun frame(bytes: ByteArray, id: Int, offset: Int, mtu: Int): ByteArray {
        require(id in 1..65535 && offset in bytes.indices && bytes.size <= MAX_ARTWORK)
        val capacity = capacity(mtu)
        require(capacity > 0)
        val end = (offset + capacity).coerceAtMost(bytes.size)
        val flags = (if (offset == 0) 1 else 0) or (if (end == bytes.size) 2 else 0)
        return ByteBuffer.allocate(HEADER_SIZE + end - offset).order(ByteOrder.LITTLE_ENDIAN)
            .put(VERSION.toByte()).put(flags.toByte()).putShort(id.toShort())
            .putInt(offset).putInt(bytes.size).put(bytes, offset, end - offset).array()
    }

    data class Frame(val id: Int, val offset: Int, val total: Int, val payload: ByteArray)
    fun decodeFrame(bytes: ByteArray): Frame {
        require(bytes.size in (HEADER_SIZE + 1)..512)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.get().toInt() == VERSION)
        val flags = b.get().toInt() and 255
        val id = b.short.toInt() and 65535
        val offset = b.int
        val total = b.int
        val length = bytes.size - HEADER_SIZE
        require(id != 0 && total in 1..MAX_ARTWORK && offset >= 0 && offset <= total - length)
        require(flags == ((if (offset == 0) 1 else 0) or (if (offset + length == total) 2 else 0)))
        return Frame(id, offset, total, bytes.copyOfRange(HEADER_SIZE, bytes.size))
    }

    data class Status(val event: Int, val code: Int, val id: Int, val maxKiB: Int, val offset: Long, val cachedCrc: Long)
    fun status(bytes: ByteArray): Status {
        require(bytes.size == 16 && bytes[0].toInt() == VERSION)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.position(4)
        return Status(bytes[1].toInt() and 255, bytes[2].toInt() and 255,
            b.short.toInt() and 65535, b.short.toInt() and 65535,
            b.int.toLong() and 0xffffffffL, b.int.toLong() and 0xffffffffL)
    }
    fun retryDelay(attempt: Int): Long = (1000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000)
}

/** Stop-and-wait artwork transaction, independent of Android for recovery tests. */
class ArtworkTransfer(
    private val mtu: () -> Int,
    private val begin: suspend (id: Int, size: Int, crc: Long) -> Protocol.Status,
    private val write: suspend (frame: ByteArray) -> Unit,
    private val commit: suspend (id: Int) -> Protocol.Status,
) {
    suspend fun send(bytes: ByteArray, id: Int) {
        require(bytes.isNotEmpty() && bytes.size <= Protocol.MAX_ARTWORK)
        val crc = Protocol.crc32(bytes)
        var last: Exception? = null
        repeat(3) {
            try {
                val start = begin(id, bytes.size, crc)
                if (start.code == 3) {
                    check(start.cachedCrc == crc) { "Cached artwork CRC mismatch" }
                    return
                }
                check(start.code == 1) { "Artwork begin status ${start.code}" }
                var offset = start.offset.toInt()
                check(offset in 0..bytes.size)
                while (offset < bytes.size) {
                    val frame = Protocol.frame(bytes, id, offset, mtu())
                    write(frame)
                    offset += frame.size - Protocol.HEADER_SIZE
                }
                val result = commit(id)
                check(result.code == 2 || result.code == 3) { "Artwork commit status ${result.code}" }
                check(result.cachedCrc == crc) { "Artwork cache CRC mismatch" }
                return
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                last = failure
                kotlinx.coroutines.delay(150L * (it + 1))
            }
        }
        throw last ?: IllegalStateException("Artwork transfer failed")
    }
}
