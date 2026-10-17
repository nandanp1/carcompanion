# Android Auto coexistence

Car Companion is a separate phone application and a BLE data peripheral. It does not register an Android Auto screen, create an audio session, request audio focus, change the output device, connect a Bluetooth audio profile or call private Android Auto APIs. The Alpine head unit continues to receive audio and navigation through the normal Android Auto connection.

Wired Android Auto and the BLE link use different interfaces. Wireless Android Auto additionally uses the phone's radios; simultaneous BLE performance depends on phone, Android build, Alpine firmware, RF conditions and power management. The architecture supports both, but this repository cannot establish compatibility with an untested phone/head-unit pair.

Spotify metadata and control access depend on its active Android MediaSession, permission to access notifications and the actions that Spotify exposes. Commands are optional: if an action is unsupported, the app logs it and leaves playback unchanged. Android Auto remains in charge of its normal presentation and routing.

Google Maps instructions are best-effort public notification text. They can be missing, formatted differently, localized, suppressed or changed while Android Auto is active. The app diagnostics show the actual notification fields and the parser's decision. No screen scraping or AccessibilityService is used. See [navigation integration](navigation-integration.md).

Run both wired and wireless cases in the [hardware checklist](testing.md). Record exact phone model, Android build, Spotify/Maps versions and Alpine firmware. Check audio continuity, route changes, locked-screen reconnects, controls, lost GPS and signal loss. Do not mark an Android Auto combination supported solely because the APK or firmware compiles.

Sources: [Android BLE background guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/background), [MediaSessionManager](https://developer.android.com/reference/android/media/session/MediaSessionManager).
