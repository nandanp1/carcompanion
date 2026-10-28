# SDK migration validation — 2026-10-27

Car Companion 0.2.0 replaces live notification parsing with app-owned Navigation SDK guidance. These checks were run locally; they do not establish Google Cloud authorization or live road performance.

| Check | Executed result |
| --- | --- |
| Android `testDebugUnitTest assembleDebug lintDebug` with Navigation SDK 8.0.0 | Passed; 129 unit tests, zero failures/errors; lint zero errors and 33 warnings; debug APK generated |
| New destination parser tests | 10 passed: short-link extraction, place IDs, destination coordinates, viewport rejection, addresses, directions paths, host/redirect validation, multi-stop rejection, invalid coordinates and ambiguous links |
| New SDK feed mapping tests | 5 passed: structured maneuver/road independence, distance/time formatting, rerouting, stop/unknown state, stale feed, unsupported maneuvers and missing measurements |
| Firmware host state/protocol and production LVGL render tests | Both passed with existing sanitizer configuration after the navigation header attribution change |
| Android API 31 emulator APK install | Passed |
| Shared coordinate destination | Confirmation UI rendered and inspected in a screenshot and UI hierarchy |
| Location and Nearby devices denial | Both system prompts exercised; app returned a clear retry message without starting a trip |
| Repeated share into an existing activity | Valid destination replaced by an unsupported URL using `SINGLE_TOP`; old destination cleared and an error shown |
| Real shortened Maps link, address geocoding, SDK authorization, billing, terms acceptance and live route | Not tested; no physical phone connected, and no navigation terms accepted by the agent |
| Locked phone, rerouting on the road, arrival, trip replacement while guiding, Spotify audio coexistence and physical BLE guidance | Not tested; see current SDK acceptance checklist in testing.md |
| Credential handling | Key stored only in ignored local.properties; source/staged-content scan performed before commit; APK contains the build-time key and is not published to GitHub |

The SDK requires AGP 9.0.1 or newer and the NIO flavor of Java desugaring. The build was updated to AGP 9.0.1 and `desugar_jdk_libs_nio:2.1.5`. The BLE wire format remains 1.0. Historical results below concern the previous notification implementation and must not be read as SDK validation.

---

# Executed validation — 2026-10-17

These results describe local builds and tests actually executed for Car Companion 0.1.0 / BLE protocol 1.0. They are not physical hardware certification.

| Check | Executed result |
| --- | --- |
| Android `:app:assembleDebug` | Passed; installable debug APK generated |
| Android `:app:testDebugUnitTest` | 114 tests, zero failures, zero errors |
| Android `:app:lintDebug` | Passed with zero errors; 33 nonblocking warnings |
| ESP-IDF 5.5.3 `set-target esp32c6`, `build`, `size` | Passed; native ESP32-C6 bootloader, partition table, app binary and ELF generated |
| Firmware CMake/CTest host behavior suite | Passed |
| Firmware host suite with AddressSanitizer + UndefinedBehaviorSanitizer | Passed |
| Python protocol reference tests | 16 tests passed |
| Actual C++ firmware parser vs independent Python oracle under ASan/UBSan | 19,440 operations agreed; passed |
| Android 12 / API 31 emulator installation and cold launch | Passed |
| Runtime precise-location and Nearby Devices permission prompts | Exercised and granted through Android UI |
| Three preview pages, Setup and Diagnostics | Opened and checked using screenshots and native UI hierarchy |
| Foreground demo service and generated JPEG cover | Ran; DEMO artwork rendered through the production encoder/state path |
| Fresh Android 13 / API 33 emulator, normal app rendering with SwiftShader/ANGLE | Three pages and demo artwork rendered correctly across repeated changes; no AndroidRuntime errors logged |
| ESP32-C6 USB flashing and startup | Initial installation followed a verified full 16 MiB original-flash backup. The performance update additionally backed up the previous 3 MiB app partition, wrote only the app, and independently verified its flash digest; NVS/bootloader/partition table were preserved. |
| Display/touch initialization and BLE startup | Performance firmware initialized CO5300, CST9220 ID `0x9220`, 480×480 touch, two DMA stripes and 16 ms refresh/input target. It reconnected automatically, encrypted the link and negotiated MTU 247. |
| Pixel 9 Pro XL / Android 17 (API 37) BLE connection | Lowercase saved-address bug reproduced and fixed; existing association reused, encrypted service writes and firmware handshake succeeded at fallback MTU 23 |
| Physical phone background connection check | App returned Connected after roughly 90 seconds in the background; foreground service remained active with the same process, and a 35-second continuous BLE-event capture recorded no connection changes |
| Physical artwork and responsiveness | User confirmed the cover appears and title/swipe updates feel faster after flashing the performance firmware. Formal color/orientation and sustained-frame-rate measurements remain unperformed. |
| Actual Spotify MediaSession and JPEG transfer | Android metadata callbacks and verified transfers observed. Updated physical firmware received an 8,002-byte cover, decoded 240×240 RGB565 at full scale and attached its image in 2,092 ms total; matching metadata/cache CRC and visible image flag confirmed. User subsequently confirmed the cover appears and updates feel faster. |
| Google Maps public notification capture | Verified on Pixel 9 Pro XL / Android 17 with Maps 26.41.04.993332563; parsed actual distance-prefixed maneuver and ETA. GPS road drive not run. |
| Alpine wired/wireless Android Auto coexistence | Not run: requires the actual vehicle/phone/head unit |
| GitHub Actions | All three jobs (Android, ESP32-C6 firmware and protocol/host tests) passed for implementation commit `4d16ac0`, run `39063125686`. The initial SDK setup failure was fixed by installing `platform-tools` instead of the retired `tools` package. |

Android lint warnings concern optional KTX style changes, newer dependency versions, compatibility constants safely passed through ServiceCompat, and version-specific manifest attributes. Dependencies stay pinned to the tested compatible set. Lint success is not a claim that there are zero warnings.

JUnit coverage comprises navigation parser (13), route notification tracking (11), removal reconciliation (4), speed policy (8), speed-limit providers (6), media identity/progress (5), page policy (3), BLE framing/artwork transactions (12), state encoder (8), connection lifecycle cancellation/replacement (2), Bluetooth address normalization (5), priority scheduling/ATT cancellation (5), latest-artwork and immediate-metadata delivery (11), adaptive progress delivery (5), selected-association identity (6), reconnect admission/GPS policy (6), and interruptible retry waits (4). Maps fixtures include the structure of one captured Maps release with its road name replaced by Sample Rd; other supported grammars remain synthetic fixtures.

On the physical phone, CompanionDeviceManager returned a lowercase address. The original client rejected it before opening GATT, while the foreground service still ran. The corrected client validates the address shape, normalizes with `Locale.ROOT`, and migrates the saved address automatically. The actual connection completed discovery, notification subscription and the protocol hello exchange without deleting the bond. Android returned status 6 for the larger MTU request; the implemented MTU 23 fallback completed the handshake successfully.

Firmware host checks cover page/gesture policy, stale/disconnected GPS, unknown/low-confidence limits, framing/CRC, duplicates, interrupted data, one-time JSON application, invalid UTF-8, excessive JSON nesting and bounded JPEG input/output callbacks. They do **not** execute the ESP32-C6 ROM JPEG decoder on the host. The ROM decoder is linked in the successful C6 cross-build. Successful artwork COMPLETE statuses were observed on the physical device; visible image quality still needs confirmation.

The subsequent device installation preserved the original full flash in a private, Git-ignored `backups/` folder with a checksum, device verification log and restore instructions. Original firmware was `magic8` 0.2.1. Only the new bootloader, partition table and application were written; NVS/PHY ranges were preserved during flashing. First boot performed the expected RF calibration because the original calibration region was empty. Successful initialization logs do not establish visible pixel quality, gesture behavior or a phone connection.

The previously installed performance-build application binary is 1,380,816 bytes. The factory flash image is 1,446,352 bytes. The cross-build's static DIRAM usage is 213,832 bytes, leaving 238,280 bytes before runtime allocations. This build was subsequently flashed and independently verified on the board. This is a linker report, not a runtime free-heap or largest-contiguous-block measurement. With the observed 240×240 cover, the updated firmware reported 123,292 bytes of free heap, a 106,496-byte largest block, and 1,156 bytes of minimum remaining JPEG-task stack. Maximum-size image and long-run margins remain acceptance checks. The JPEG worker falls back to a 120×120 decode if the full 240×240 allocation cannot be satisfied. Use `idf.py size` for the exact current build report.

**Known visual issue:** the existing API 31 emulator (35.1.21) intermittently omitted portions of text after demo/page updates. Some cold launches rendered correctly, but restarting is not a verified workaround; accessibility strings, bounds and controls remained intact. The issue reproduced with hardware acceleration and a temporary software-rendering experiment, and after updating Compose UI/runtime from 1.9.0 to 1.9.5. Its cause remains unresolved; it has not been proven emulator-only. The final app retains normal hardware acceleration and the pinned Compose patch update. Check repeated page changes on a physical phone before accepting the Android UI.

A fresh isolated API 33 emulator using SwiftShader/ANGLE rendered full clock text, all page buttons, footer text and album artwork correctly across music→navigation→driving→music and repeated live updates. No AndroidRuntime errors were logged. This narrows the observed failure to the earlier API 31 environment; it does not establish correctness on every Android 12 device. The scratch emulator was stopped after testing and the existing emulator's settings were preserved.

Reproduce the automated checks using [testing.md](testing.md), [Android setup](android-setup.md) and [hardware setup](hardware-setup.md). Complete the manual acceptance checklist before using the display on the road.

## Music latency investigation

The original sender could finish an obsolete JPEG before handling the latest queued cover. The updated sender cancels obsolete work at an ATT boundary, prioritizes significant music changes, abandons stale partial metadata, and reduces progress-only resynchronization on small MTUs. Tests exercise cancellation while an ATT operation is outstanding so callbacks cannot be reassigned to the next operation.

On the physical phone, MTU 23/status 6 produced a 1,489 ms metadata send and an 83,348 ms transfer for a 9,672-byte JPEG. A subsequent reconnect successfully negotiated MTU 247; one 187-byte metadata message completed in 96 ms. During an actual Spotify track change, the initial metadata send completed approximately 573 ms after the callback, updated artwork identity followed in 201 ms, and a 10,914-byte JPEG completed with firmware CRC/decode verification in 2,759 ms. These are observed samples on the earlier display firmware, not guarantees or panel-visible latency measurements. Spotify itself took approximately 2.1 seconds after the injected next-track command to emit new metadata; the companion cannot publish metadata before Spotify exposes it.

The final Android initialization now negotiates MTU after the encrypted hello exchange. This avoids overlapping negotiation with establishing link encryption, but encryption timing has not been proven to cause the earlier status 6. Failed negotiation still uses the actual MTU 23 fallback. The newer firmware uses semaphore-based DMA completion, two 10-line stripes, a 16 ms refresh/input target, and immediate state wakeups. Hardware installation and live render/heap checks subsequently completed, as recorded below.

After moving MTU negotiation after encrypted hello, two observed app reconnects both negotiated MTU 247 and restored metadata in 88 ms and 99 ms respectively. The current artwork CRC was reused; no duplicate JPEG transfer was logged for those reconnects. This limited sample does not establish a universal fix for every phone or RF environment.

A live callback sequence also exposed repeated bitmap objects encoding to identical JPEG bytes. The artwork sender now ignores identical CRC offers only while that cover is queued or transferring. A null/track-clear event, disconnect, completion or failure allows replay; three regression tests cover duplicate callbacks and same-cover reconnection during cancellation cleanup.

A separate host render smoke harness used the pinned LVGL 9.5 source, generated firmware configuration, production MusicScreen code and fonts. Pixel assertions passed for 240×240 RGB565 artwork, mismatched-CRC hiding, valid CRC zero, and 120×120 replacement. 111 deferred stripe transfers completed without early buffer reuse; ASan reported no memory errors. UBSan reported two upstream LVGL callback function-pointer type mismatches, so this additional render harness is **not UBSan-clean**. The existing protocol/state sanitizer suite remains a separate passing check. Host render output was visually inspected; this does not certify the physical panel or its frame rate.

## Performance firmware installed on the physical board

The original factory backup checksum was rechecked before this update. The previous application partition was also saved privately for rollback; its application prefix matched the previously installed binary hash. Only the application at 0x10000 was written, and esptool independently verified the new digest.

The board automatically reconnected to the existing phone bond. Logs show successful encryption, ATT MTU 247, 251-byte link payloads in both directions, and a high-priority interval of 15 ms during artwork transfer. The 8,002-byte JPEG arrived in 35 fragments; receive/commit took 2,011 ms, ROM decoding took 73 ms, and completion including image attachment took 2,092 ms. It decoded at full 240×240 resolution without the half-scale fallback. LVGL accepted the image source; the current music CRC matched the decoded CRC, and the image visibility flag became true. This verifies the complete app-to-firmware-to-LVGL path, and the user subsequently confirmed visible cover art and faster title/swipe response. Color/orientation accuracy and sustained frame rate were not instrumented.

The first render metrics included an initial full-screen maximum of 69,451 µs; a later partial update took 4,205 µs. These measurements are rendering durations, not a measured sustained frame rate. The 16 ms timer is a scheduling target; the documented 40 MHz QSPI bandwidth limit still applies.

During a second observed startup, the existing bond reconnected again at MTU 247, and the same full-size cover decoded and attached in 2,248 ms. No panic or watchdog markers appeared in either capture. The user explicitly reported: “Cover appears and updates feel faster.”

## Full-size artwork retained across tracks

The first performance build exposed heap fragmentation during a replacement cover: after freeing the original 115,200-byte pixel buffer, the largest free block was 114,688 bytes, forcing 120×120 fallback despite 238,448 total free bytes. The final build transfers move-only ownership of the existing pixel buffer from the display to the decoder after detaching all LVGL references. It reuses sufficient capacity, then returns ownership after successful decoding. Failed growth/decode still releases the sole allocation safely.

The additional ownership/reuse tests passed AddressSanitizer and UndefinedBehaviorSanitizer, including forced allocation failure after the initial allocation, retained capacity after a smaller image, move ownership, bounded growth failure and half-scale recovery. The final ESP-IDF build passed and its app-only flash was independently verified on the device; binary SHA256 is `20537f7961534f88ded2e54937b576e910ead89821f571cd92015848d2895035`.

Physical logs captured three distinct covers. The first 12,136-byte JPEG allocated 115,200 bytes; the next 12,439-byte and 6,107-byte JPEGs both reported `reusable=115200`, decoded at `scale=0`, and attached 240×240 images with matching CRCs. They completed in 3,324/3,391/1,626 ms respectively. Free heap remained approximately 123,232 bytes, largest free block 106,496 bytes, and JPEG task stack margin 1,152 bytes. No panic/watchdog markers or allocation fallback appeared. The user confirmed: “Yes, the new cover stays full size.” This is a short bench test; the one-hour soak and vehicle coexistence checks remain outstanding.

## Automatic reconnect, launcher icon and long music names

The Android update passed 95 JUnit tests, zero failures/errors, `assembleDebug`, and lint with zero errors / 33 warnings. It was installed successfully on the physical Pixel 9 Pro XL, preserving the saved display and permissions. The enabled service migrated to `auto_reconnect=true`, resolved its existing companion association, and ran with sticky restart semantics. The new adaptive vector launcher icon was rendered and visually checked in Android 13 app settings; the physical phone's updated Overview and Automatic reconnect setup were also inspected.

With the app backgrounded, its process was deliberately killed (not Android force-stop). The system restarted it; subsequent inspection confirmed a new process with a sticky `connectedDevice` foreground service only (`types=0x10`). Opening the app restored the already-permitted location foreground type (`0x18`). This checks real Android 17 process recovery without pretending that background location restrictions disappear. Automatic reconnect remains enabled on the phone. Physical deliberate-Stop/presence behavior and OEM variants remain separate checks.

Android APK SHA256: `79a9c30ebfd4b28325beea08c25a0e8a64221c4de1083d30c033804fa74325a2` (18,665,126 bytes).

The actual LVGL host test reproduced the original stacking: long title/artist/album labels grew to 135/66/36 pixels. The corrected labels remain 27/22/18 pixels high, flatten line breaks, truncate with ellipsis and do not overlap controls. Twenty unchanged updates produced zero additional flushes; artwork association/CRC checks and 178 deferred stripe transfers also passed. Both CTest targets passed with ASan and UBSan, excluding Clang's function-pointer type check in the pinned LVGL callback implementation as documented in the test CMake file. Rendered music and dashboard output were visually inspected.

The ESP-IDF build passed: application 1,382,304 bytes, static DIRAM 213,928 bytes and 238,184 bytes remaining before runtime allocations. Firmware SHA256: `2bc6b0ed154c684e3708ad6175a3f7c1a196cf5dcbf7001e51bb3e90d15895e3`. Before installation, the original full-flash backup checksum was rechecked and the currently installed 3 MiB app partition was read into a new private backup; its application prefix matched the previous firmware digest. Only application offset `0x10000` was written and independently verified. NVS bonds, bootloader and partition table were preserved. Post-installation reconnect results follow.

On the newly flashed firmware, the controlled boot log showed BLE connected at 1,632 ms, encrypted/bonded at 1,801 ms, application handshake at 1,988 ms, and MTU 247 at 2,103 ms. A 6,285-byte cover transferred and decoded at full 240×240 resolution in 1,783 ms, with the matching image visible flag set at boot time 4,280 ms. No panic/watchdog markers appeared. After being asked to lock the phone and remove USB power for five seconds with the battery disconnected, the user confirmed: “Yes, it reconnects automatically.” This is a successful reported bench power-cycle check, not an instrumented vehicle ignition or long-term soak test.


## Compact covers and automatic Maps navigation

The updated Android build passed 114 JUnit tests with zero failures/errors, `assembleDebug`, and lint with zero errors / 33 warnings. Its APK is 18,665,126 bytes, SHA256 `31604571526e83824988d127fdbe2477884e48e7339c881e75d83271bf433f6a`. It was installed on the physical Pixel 9 Pro XL and an isolated API 33 emulator. The physical phone retained its existing permissions, companion association and automatic reconnect setting.

A real ongoing Maps 26.41.04.993332563 notification on Android 17 supplied its maneuver in a distance-prefixed title with Unicode spacing and a middle dot. The previous parser missed this instruction. After the update, discovery of the already-active route switched the phone preview from its saved Driving page to Navigation and displayed the correct keep-right arrow, distance, road and arrival time. Actual Spotify artwork was visually checked in both Driving and Navigation previews. A later unchanged notification produced the intended unavailable-instruction state while Navigation remained active. The private capture contains location data and is excluded from the repository; regression tests use a synthetic road name. A moving GPS road test, additional Maps versions/locales and controlled physical route-end/manual-override checks remain outstanding.

The real LVGL render suite and firmware state suite both passed CTest (2/2) with the documented sanitizer configuration. Pixel assertions cover shared 52×52 artwork on Driving and Navigation, CRC mismatch hiding, valid CRC zero, source detachment before freeing/replacing the single pixel buffer, and 120×120 fallback. Rendered layouts were visually inspected for circular bounds, guidance spacing and single-line footer text. Peak LVGL allocation in the host renderer was 25,672 bytes; thumbnail transforms use bounded 3,840-byte stripes. The ESP32 still owns one decoded artwork buffer shared by all three pages.

The ESP-IDF build passed: application 1,382,768 bytes, static DIRAM 214,104 bytes and 238,008 bytes remaining before runtime allocations. Firmware SHA256: `865f742297d1590837e8c0843920988dda5b97860ec21087fa6d20a53f8228b8`.

Before installation, the original full-flash backup digest was rechecked and the installed 3 MiB application partition was backed up privately; its prefix matched the prior `2bc6b0ed…` binary. The new application was written only at `0x10000` and independently verified by esptool, preserving NVS bonds, bootloader and partition table. On controlled boot, BLE connected at 1,699 ms, encrypted at 1,886 ms, completed the application handshake at 2,185 ms and negotiated MTU 247 at 2,282 ms. A 7,910-byte JPEG arrived in 35 fragments and decoded at full 240×240 scale; transfer through image attachment completed in 2,148 ms. Matching artwork CRC and visible image flag were logged at 4,824 ms after boot. Free heap settled around 122,800 bytes with a 106,496-byte largest block and 1,160-byte JPEG task stack margin. No panic or watchdog markers appeared during the 40-second capture. These logs verify connection, transfer, decode and LVGL attachment.

After being asked to check the cover on both Driving and Navigation, then end and restart the Maps route while parked, the user confirmed: “Covers appear; Navigation opens automatically.” This adds a physical visual confirmation for the two thumbnails and route activation. Sustained vehicle use, precise route-start latency and the complete manual override/route-end checklist remain separate acceptance checks.
