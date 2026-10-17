# Android setup — Car Companion

## Build and install

Open `android/` in Android Studio. Install Android SDK 36 and Build Tools 36.0.0, and use JDK 17 or newer. The checked-in Gradle wrapper downloads Gradle 9.3.1. Dependencies are pinned: Android Gradle Plugin 9.0.0 with its built-in Kotlin 2.2.10, the matching Compose compiler plugin, Compose BOM 2025.11.01 (Compose UI/Foundation/Runtime/Animation 1.9.5 and Material 3 1.4.0), AndroidX lifecycle 2.9.3, coroutines 1.10.2 and Play Services Location 21.3.0. This deliberately uses a consistent stable toolchain rather than unpinned latest releases.

```sh
cd android
# Set ANDROID_HOME to your Android SDK, or create an untracked local.properties:
# sdk.dir=/absolute/path/to/Android/sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The application ID is `com.carcompanion.app`; the launcher name is **Car Companion**. Minimum Android version is 8.0 / API 26; target and compile SDK are 36. Devices without Google Play Services cannot supply Fused Location fixes; the display continues with unavailable GPS speed. The repository does not include a production signing key. Debug APKs are for development; configure your own release signing before distributing builds.

## Initial connection

1. While parked, power the Waveshare display with the firmware installed and advertising `Car Companion`.
2. Open the app and choose **Connect a display**, or **Setup → Find nearby display**.
3. Grant Nearby devices permissions (Android 12+), precise location for GPS, and notifications (Android 13+). Location can be denied without inventing a speed; the app reports unavailable GPS. Notification denial can hide the ongoing notification from the drawer; Android still shows the foreground-service activity in its task manager.
4. The Android Companion Device Manager chooser filters by the custom BLE service UUID. Select your physical display. Confirm the Android Bluetooth pairing dialog when requested. Association and Bluetooth bonding are separate operations; the client requests both. Pair near your own hardware in a trusted environment because the display uses no-input/no-output bonding.
5. In **Setup → Open notification access**, enable **Car Companion: Maps & media**. Android treats this as special access, not an ordinary permission prompt. It enables active Spotify session access and Maps notification inspection. The code ignores notifications from other packages, and notification diagnostic text stays in bounded process memory.
6. Tap **Start companion** while the app is visible. A persistent foreground notification keeps BLE and GPS active as Android permits. It has a **Stop** action. The app does not request Bluetooth audio profiles, media focus, microphone access, AccessibilityService access, or private Android Auto APIs.

Some Android versions require Location Services enabled for the system companion chooser. If the chooser finds nothing, confirm the firmware is advertising, Bluetooth is enabled, and the display is not connected to another phone. Re-association after resetting the board may also require forgetting its old bond in Android Bluetooth settings. Use the system app's association/bond settings to remove old devices; the app never silently removes bonds.

## While driving

The foreground service keeps working when this activity closes. BLE reconnects with capped exponential backoff. Android Companion Device Manager presence observation can wake a previously enabled companion service when the display returns. The Android 16 presence API is used on Android 16; the older supported API is used on Android 12–15.

A cold presence wake starts a `connectedDevice` foreground service only. Android's while-in-use location restrictions mean you must open the app and tap **Start companion** to restore GPS after the service process was stopped. The app does not claim unrestricted background GPS and does not request background-location permission. Force stop, battery restrictions, Bluetooth-off, permission revocation, OEM policies, or a killed service can require a visible restart. A deliberate **Stop companion** disables automatic presence restart.

The app restores metadata on each successful connection. It reads the firmware's cached artwork CRC and resends the JPEG only when needed. Android may negotiate MTU 517 even though the client requests 247; the actual result is used, with an ATT value cap of 512 bytes and a 12-byte frame header. MTU 23 is supported. Transfers use one acknowledged write at a time, bounded three-attempt artwork transactions, and no assumed throughput.

## Spotify and artwork

Play a track in the installed Spotify app with notification access granted. The app selects Spotify's active `MediaSession`, reads public metadata and artwork, and sends bounded baseline JPEG images. It accepts an exposed bitmap, a permitted local content/resource URI, or an HTTPS artwork URI supplied by Spotify's session. HTTPS downloads are capped at 2 MiB with five-second timeouts and redirects disabled. No Spotify Web API credentials are required.

Artwork starts at 240 × 240 and the configurable JPEG quality in Setup. Quality and then resolution are reduced if the image exceeds 32 KiB. The ESP32 verifies the compressed bytes' CRC32 before decoding. Firmware play/pause, next and previous actions go through Spotify's active session and are gated by that session's advertised supported transport actions. Spotify may omit artwork or restrict actions in a given session; diagnostics report the actual integration state.

## Navigation and limits

Start a Google Maps route, then inspect **Diagnostics → Maps notification inspector**. Only explicitly recognized textual directions produce arrows. Notification layouts, locale, Android versions and Maps versions vary; there is no guarantee that maneuver, distance, road or ETA is exposed. Unknown fields remain empty. English parser coverage and a separate provider interface are documented in `navigation-integration.md`.

Speed-limit default is **Unavailable**. Setup also supports a demo-only mock and a configurable road-matched HTTPS adapter. See `speed-limits.md` for the response contract, confidence and age requirements, credentials, coverage, licensing and privacy responsibilities. Selecting the HTTPS adapter sends coordinates only to the endpoint you configure. Never configure an API key in source control; the app saves its user-supplied endpoint/token privately and disables Android backup.

## Preview and demo

Overview renders the same `DisplayState` consumed by the BLE encoder. Swipe or tap a page name to preview the three views. The physical display's own gestures select its page independently. Navigation route start/end updates both models using the documented page policy. Preview uses an unambiguous dash for missing GPS and speed limits, and reports data age/confidence in Diagnostics.

**Explore demo mode** starts a visibly labeled synthetic speed, music and route sequence. Android also draws a deterministic demo album cover with color patches and passes it through the real JPEG encoder, CRC calculation and BLE artwork transfer. This exercises the image pipeline without a Spotify session. When connected, demo mode is explicitly signaled to the display. Turn it off before live use. Firmware also has an independent simulation build option, described in the hardware setup guide.

## Android Auto coexistence

Connect the phone to the Alpine head unit normally, either wired or wirelessly, then run the companion service. Android Auto and Spotify continue to own audio and navigation. Car Companion is an independent GATT client and does not appear as an audio endpoint. Simultaneous Bluetooth/Wi-Fi radio behavior and Android background policy vary by phone/head-unit combination, so both modes require the manual hardware checklist. No physical Android Auto coexistence test is implied by an APK build or emulator test.

## References

- [Companion device pairing and device presence](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing)
- [Bluetooth GATT client APIs, MTU and write callbacks](https://developer.android.com/reference/android/bluetooth/BluetoothGatt)
- [Android Gradle Plugin 9.0 compatibility and built-in Kotlin](https://developer.android.com/build/releases/agp-9-0-0-release-notes)
- [Compose BOM dependency alignment](https://developer.android.com/develop/ui/compose/bom)
