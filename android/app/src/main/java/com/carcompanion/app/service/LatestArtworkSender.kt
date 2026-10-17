package com.carcompanion.app.service

import com.carcompanion.app.model.Artwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow

/** One current cover, including the transfer already in progress. Null cancels an obsolete cover. */
internal class LatestArtworkSender(
    private val isCurrent: (Artwork) -> Boolean,
    private val publishMetadata: suspend () -> Unit,
    private val transfer: suspend (Artwork) -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private class Request(val artwork: Artwork)
    private val requests = Channel<Request?>(Channel.CONFLATED)
    private val lock = Any()
    private var pendingOrActive: Request? = null

    fun offer(artwork: Artwork?) = synchronized(lock) {
        // MediaSession can repeatedly expose fresh Bitmap objects for the identical JPEG.
        // Keep the current transfer; a clear/disconnect or a completed/failed attempt permits retry.
        if (artwork != null && pendingOrActive?.artwork?.contentId == artwork.contentId) return@synchronized
        val request = artwork?.let(::Request)
        pendingOrActive = request
        requests.trySend(request)
        Unit
    }

    suspend fun run() {
        requests.receiveAsFlow().collectLatest { request ->
            if (request == null) return@collectLatest
            val artwork = request.artwork
            try {
                if (!isCurrent(artwork)) return@collectLatest
                publishMetadata()
                // The track may have changed while its previous metadata write completed.
                if (isCurrent(artwork)) transfer(artwork)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFailure(failure)
            } finally {
                synchronized(lock) {
                    // An old transfer may finish cancellation after its replacement was queued.
                    if (pendingOrActive === request) pendingOrActive = null
                }
            }
        }
    }
}
