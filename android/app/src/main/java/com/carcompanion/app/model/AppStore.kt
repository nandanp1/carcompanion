package com.carcompanion.app.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Process-local UI state. Navigation SDK networking is owned by the navigation provider. */
object AppStore {
    private val mutableState = MutableStateFlow(DisplayState())
    val state: StateFlow<DisplayState> = mutableState.asStateFlow()
    private val mutableDiagnostics = MutableStateFlow(Diagnostics())
    val diagnostics: StateFlow<Diagnostics> = mutableDiagnostics.asStateFlow()
    private val mutableArtwork = MutableStateFlow<Artwork?>(null)
    val artwork: StateFlow<Artwork?> = mutableArtwork.asStateFlow()
    private var userPage = DisplayPage.DRIVING
    private var previousPage = DisplayPage.DRIVING

    fun update(transform: (DisplayState) -> DisplayState) = mutableState.update(transform)
    fun diagnostic(transform: (Diagnostics) -> Diagnostics) = mutableDiagnostics.update(transform)
    fun artwork(value: Artwork?) { mutableArtwork.value = value }
    @Synchronized fun selectPage(page: DisplayPage) {
        userPage = page
        update { it.copy(page = page) }
    }
    @Synchronized fun navigation(value: NavigationState) {
        val wasActive = state.value.navigation.active
        if (value.active && !wasActive) previousPage = userPage
        if (!value.active && wasActive) userPage = previousPage
        update { it.copy(navigation = value, page = when {
            value.active && !wasActive -> DisplayPage.NAVIGATION
            !value.active && wasActive -> previousPage
            else -> it.page
        }) }
    }
    fun log(message: String) = diagnostic {
        it.copy(logs = (listOf(message.take(240)) + it.logs).take(80))
    }
    fun notification(entry: NotificationDiagnostic) = diagnostic {
        it.copy(notifications = (listOf(entry) + it.notifications).take(12))
    }
}
