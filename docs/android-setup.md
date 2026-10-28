# Android setup — Car Companion

## Build and install

Open `android/` in Android Studio. Install Android SDK 36 and Build Tools 36.0.0, and use JDK 17 or newer. The checked-in Gradle wrapper downloads Gradle 9.3.1. Dependencies are pinned: Android Gradle Plugin 9.0.1 with its built-in Kotlin 2.2.10, the matching Compose compiler plugin, Compose BOM 2025.11.01 (Compose UI/Foundation/Runtime/Animation 1.9.5 and Material 3 1.4.0), AndroidX lifecycle 2.9.3, coroutines 1.10.2 Play Services Location 21.3.0, Navigation SDK 8.0.0 and desugar_jdk_libs_nio 2.1.5. This deliberately uses a consistent stable toolchain rather than unpinned latest releases.

```sh
cd android
# Set ANDROID_HOME to your Android SDK, or create an untracked local.properties:
# sdk.dir=/absolute/path/to/Android/sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The application ID is `com.carcompanion.app`; the launcher name is **Car Companion**. Minimum Android version is 8.0 / API 26; target and compile SDK are 36. Devices without Google Play Services cannot supply Fused Location fixes; the display continues with unavailable GPS speed. The repository does not include a production signing key. Debug APKs are for development; configure your own release signing before distributing builds.

## Google Navigation SDK configuration

Enable **Navigation SDK** in your Google Cloud project and attach billing. In ignored `android/local.properties`, add `MAPS_API_KEY=your_key_here`; CI can use the `MAPS_API_KEY` environment variable instead. The build injects it into `com.google.android.geo.API_KEY`. Never commit the real key or publish a development APK containing an unrestricted key.

Restrict the key to Android application `com.carcompanion.app` and the SHA-1 of your signing certificate (`./gradlew :app:signingReport`), and restrict its API access to the Navigation SDK. Debug and release certificates differ. A missing or unauthorized key produces a visible startup error. A build without a key is allowed for tests, but live navigation will not authorize.

Google’s current SDK policy specifies commercial applications only; verify eligibility for this personal project before live use. See [migration details and policy source](navigation-integration.md).

## Initial connection

1. While parked, power the Waveshare display with the firmware installed and advertising `Car Companion`.
2. Open the app and choose **Connect a display**, or **Setup → Find nearby display**.
3. Grant Nearby devices permissions (Android 12+), precise location for GPS, and notifications (Android 13+). Location can be denied without inventing a speed; the app reports unavailable GPS. Notification denial can hide the ongoing notification from the drawer; Android still shows the foreground-service activity in its task manager.
4. The Android Companion Device Manager chooser filters by the custom BLE service UUID. Select your physical display. Confirm the Android Bluetooth pairing dialog when requested. Association and Bluetooth bonding are separate operations; the client requests both. Pair near your own hardware in a trusted environment because the display uses no-input/no-output bonding.
5. In **Setup → Open notification access**, enable **Car Companion: Spotify media**. Android treats this as special access, not an ordinary permission prompt. It enables active Spotify session access. The listener no longer reads any notification content; navigation does not require notification access.
6. Tap **Start companion** while the app is visible. A persistent foreground notification keeps BLE and GPS active as Android permits. It has a **Stop** action. The BLE connection does not request Bluetooth audio profiles. Navigation SDK voice guidance can use the phone’s normal audio output. The app does not use microphone capture, AccessibilityService access, or private Android Auto APIs.

Some Android versions require Location Services enabled for the system companion chooser. If the chooser finds nothing, confirm the firmware is advertising, Bluetooth is enabled, and the display is not connected to another phone. Re-association after resetting the board may also require forgetting its old bond in Android Bluetooth settings. Use the system app's association/bond settings to remove old devices; the app never silently removes bonds.

## While driving

The foreground service keeps working when this activity closes. **Setup → Automatic reconnect** is enabled by the first explicit Start and preserves the existing enabled service on upgrade. Leave it on for an ignition-powered display. The display starts advertising on each cold boot; the phone reconnects using its saved association and bond without another pairing step. BLE retries use capped exponential backoff, and a selected-device presence event or Bluetooth being turned back on interrupts that wait.

Android Companion Device Manager presence observation can wake a previously enabled companion service when the display returns. The app repairs presence registration from the saved association when started, uses association IDs on Android 16+, and ignores events from other associated devices. Android 12–15 use the older supported presence API. On Android 8–11, automatic retry requires the foreground service to remain running because the presence-wake API is unavailable. Turning automatic reconnect off keeps an existing connection but stops recovery after it drops.

A cold presence wake or Android sticky-service restart starts a `connectedDevice` foreground service only. The existing live foreground service pauses GPS when the display is absent and resumes it automatically on reconnection. After its process dies, opening the already-enabled app restores permitted GPS from the visible activity; Overview also provides **Resume GPS** when a visible restart is needed. The app does not request background-location permission. Force stop, battery restrictions, permission revocation, or OEM policies can still require a visible restart. A deliberate **Stop companion** disarms both sticky recovery and presence restart until you start it again.

The app restores metadata on each successful connection. It reads the firmware's cached artwork CRC and resends the JPEG only when needed. Android may negotiate MTU 517 even though the client requests 247; the actual result is used, with an ATT value cap of 512 bytes and a 12-byte frame header. MTU 23 is supported. Transfers use one acknowledged write at a time, bounded three-attempt artwork transactions, and no assumed throughput.

## Spotify and artwork

Play a track in the installed Spotify app with notification access granted. The app selects Spotify's active `MediaSession`, reads public metadata and artwork, and sends bounded baseline JPEG images. It accepts an exposed bitmap, a permitted local content/resource URI, or an HTTPS artwork URI supplied by Spotify's session. HTTPS downloads are capped at 2 MiB with five-second timeouts and redirects disabled. No Spotify Web API credentials are required.

Artwork starts at 240 × 240 and the configurable JPEG quality in Setup. Quality and then resolution are reduced if the image exceeds 32 KiB. The ESP32 verifies the compressed bytes' CRC32 before decoding. Firmware play/pause, next and previous actions go through Spotify's active session and are gated by that session's advertised supported transport actions. Spotify may omit artwork or restrict actions in a given session; diagnostics report the actual integration state.

## Navigation and limits

In Google Maps, open a destination, tap **Share**, and select **Car Companion**. Check the resolved destination (select a candidate if needed), then tap **Start navigation**. Allow precise location and accept Google’s navigation terms on first use. The app calculates a driving route and forwards the SDK’s turn feed to the display. Use **Overview → Stop navigation** to end the trip without stopping music/BLE.

Only the destination transfers. Selected route alternatives and multi-stop routes are not imported. Directions, rerouting, and remaining trip time come from the SDK. Check **Diagnostics → Google Maps** for status. If Google rejects the key, check SDK enablement, billing, package name, and signing certificate. See [navigation integration](navigation-integration.md).

Speed-limit default is **Unavailable**. Setup also supports a demo-only mock and a configurable road-matched HTTPS adapter. See `speed-limits.md` for the response contract, confidence and age requirements, credentials, coverage, licensing and privacy responsibilities. Selecting the HTTPS adapter sends coordinates only to the endpoint you configure. Never configure an API key in source control; the app saves its user-supplied endpoint/token privately and disables Android backup.

## Preview and demo

Overview renders the same `DisplayState` consumed by the BLE encoder. Swipe or tap a page name to preview the three views. The physical display's own gestures select its page independently. Navigation route start/end updates both models using the documented page policy. Preview uses an unambiguous dash for missing GPS and speed limits, and reports data age/confidence in Diagnostics.

Driving and Navigation also show a compact current album cover alongside the song and artist, using the same artwork identity as Music. Music metadata uses one line per field, with an ellipsis for long names. Embedded line breaks are flattened for presentation. The physical display also constrains label heights so titles, artists and albums cannot overlap each other or the controls. The launcher uses a native adaptive vector instrument icon with a black background, white outline, blue needle and a monochrome layer for themed launchers.

**Explore demo mode** starts a visibly labeled synthetic speed, music and route sequence. Android also draws a deterministic demo album cover with color patches and passes it through the real JPEG encoder, CRC calculation and BLE artwork transfer. This exercises the image pipeline without a Spotify session. When connected, demo mode is explicitly signaled to the display. Turn it off before live use. Firmware also has an independent simulation build option, described in the hardware setup guide.

## Standalone display

Android Auto is no longer required. The phone runs navigation and Spotify, and sends data to the ESP32 over BLE. Voice guidance uses the phone’s configured audio output. Avoid running a separate Maps navigation session at the same time: it is independent and may choose a different route or speak competing instructions. Physical voice/music and phone-lock tests are still required.

## References

- [Companion device pairing and device presence](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing)
- [Bluetooth GATT client APIs, MTU and write callbacks](https://developer.android.com/reference/android/bluetooth/BluetoothGatt)
- [Android Gradle Plugin 9.0 compatibility and built-in Kotlin](https://developer.android.com/build/releases/agp-9-0-0-release-notes)
- [Compose BOM dependency alignment](https://developer.android.com/develop/ui/compose/bom)
- [Adaptive vector icons and monochrome layers](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)
- [Android sticky service restart behavior](https://developer.android.com/reference/android/app/Service#START_STICKY)
- [Foreground service background-start and location restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
