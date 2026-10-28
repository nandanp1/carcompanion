package com.carcompanion.app.navigation

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager

/** SDK-bound receiver. No exported endpoint or notification parsing. */
class NavigationFeedService : Service() {
    private val manager by lazy { TurnByTurnManager.createInstance() }
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == TurnByTurnManager.MSG_NAV_INFO) {
            runCatching { manager.readNavInfoFromBundle(message.data) }
                .getOrNull()?.let(GoogleNavigationSession::receive)
        }
        true
    })
    override fun onBind(intent: Intent?): IBinder = messenger.binder
}
