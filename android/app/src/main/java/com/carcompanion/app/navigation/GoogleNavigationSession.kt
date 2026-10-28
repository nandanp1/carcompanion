package com.carcompanion.app.navigation

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import com.carcompanion.app.model.AppStore
import com.carcompanion.app.model.NavigationState
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.navigation.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread, process-local owner. The companion service owns the trip lifetime. */
object GoogleNavigationSession : NavigationProvider {
    private val current = MutableStateFlow(NavigationState())
    override val updates = current.asStateFlow()
    private var navigator: Navigator? = null
    private var generation = 0
    private var acceptingFeed = false
    private var arrivalListener: Navigator.ArrivalListener? = null

    fun prepare(activity: Activity, ready: () -> Unit, failed: (String) -> Unit) {
        NavigationApi.getNavigator(activity, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(value: Navigator) {
                if (activity.isFinishing || activity.isDestroyed) return
                navigator = value
                ready()
            }
            override fun onError(code: Int) {
                if (activity.isFinishing || activity.isDestroyed) return
                val message = when (code) {
                    NavigationApi.ErrorCode.NOT_AUTHORIZED -> "Google rejected the API key. Enable Navigation SDK and billing, and check the Android app restrictions."
                    NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> "Accept Google's navigation terms to start a trip."
                    NavigationApi.ErrorCode.NETWORK_ERROR -> "Google navigation could not connect. Check your internet connection and retry."
                    NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING -> "Allow precise location to start navigation."
                    else -> "Google navigation could not start (error $code)."
                }
                status(message); failed(message)
            }
        })
    }

    fun start(context: Context, destination: SharedMapsDestination) {
        val nav = navigator ?: run { status("Open the shared destination again to initialize navigation."); return }
        stop("Calculating route…", release = false)
        val request = generation
        try {
            val builder = Waypoint.builder().setTitle(destination.label.take(200))
            if (destination.placeId != null) builder.setPlaceIdString(destination.placeId)
            else builder.setLatLng(requireNotNull(destination.latitude), requireNotNull(destination.longitude))
            nav.setTaskRemovedBehavior(Navigator.TaskRemovedBehavior.CONTINUE_SERVICE)
            nav.setDestination(builder.build(), RoutingOptions().travelMode(RoutingOptions.TravelMode.DRIVING))
                .setOnResultListener { result ->
                    if (request != generation) return@setOnResultListener
                    if (result != Navigator.RouteStatus.OK) {
                        stop("Could not calculate a driving route ($result). Share the destination to try again.", release = false)
                        return@setOnResultListener
                    }
                    try {
                        check(nav.registerServiceForNavUpdates(context.packageName, NavigationFeedService::class.java.name, 1)) {
                            "Could not attach the display guidance feed."
                        }
                        arrivalListener = Navigator.ArrivalListener { event ->
                            if (event.isFinalDestination) stop("You have arrived.", release = false)
                        }.also(nav::addArrivalListener)
                        nav.setAudioGuidanceSettings(AudioGuidanceSettings.builder()
                            .setGuidanceMode(AudioGuidanceSettings.GuidanceMode.VOICE_ALERTS_AND_GUIDANCE).build())
                        acceptingFeed = true
                        current.value = NavigationState(active = true, road = "Starting guidance…",
                            updatedAtElapsedMs = SystemClock.elapsedRealtime(), source = "Google Navigation SDK")
                        nav.startGuidance()
                        status("Navigating · Google Navigation SDK")
                    } catch (_: RuntimeException) {
                        stop("Navigation could not start. Check permissions and share the destination again.", release = false)
                    }
                }
        } catch (_: RuntimeException) {
            stop("Google could not use this destination. Try sharing a pinned location.", release = false)
        }
    }

    fun receive(info: NavInfo) {
        if (!acceptingFeed) return
        val state = SdkNavigationMapper.map(info, SystemClock.elapsedRealtime())
        current.value = state
        status(when {
            !state.active -> "Navigation stopped"
            state.road == "Rerouting…" -> "Rerouting · Google Navigation SDK"
            else -> "Navigating · Google Navigation SDK"
        })
    }

    fun stop(message: String = "Navigation stopped", release: Boolean = true) {
        generation++
        acceptingFeed = false
        navigator?.let { nav ->
            arrivalListener?.let { runCatching { nav.removeArrivalListener(it) } }
            runCatching { nav.unregisterServiceForNavUpdates() }
            runCatching { nav.stopGuidance(); nav.clearDestinations() }
            if (release) runCatching { nav.cleanup() }
        }
        arrivalListener = null
        if (release) navigator = null
        current.value = NavigationState()
        AppStore.navigation(NavigationState())
        status(message)
    }
    private fun status(message: String) { AppStore.diagnostic { it.copy(navigationStatus = message) } }
}
