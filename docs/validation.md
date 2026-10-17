# Executed validation — 2026-10-17

These results describe local builds and tests actually executed for Car Companion 0.1.0 / BLE protocol 1.0. They are not physical hardware certification.

| Check | Executed result |
| --- | --- |
| Android `:app:assembleDebug` | Passed; installable debug APK generated |
| Android `:app:testDebugUnitTest` | 58 tests, zero failures, zero errors |
| Android `:app:lintDebug` | Passed with zero errors; 28 nonblocking warnings |
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
| ESP32-C6 USB flashing and startup | Passed on the connected board after a verified full 16 MiB original-flash backup; all three firmware segments verified against device flash |
| Display/touch initialization and BLE startup | Boot log reports CO5300 initialized, CST9220 ID `0x9220`, 480×480 touch resolution and NimBLE advertising; firmware ready with no panic/watchdog failure in a 25-second capture |
| Pixel 9 Pro XL / Android 17 (API 37) BLE connection | Lowercase saved-address bug reproduced and fixed; existing association reused, encrypted service writes and firmware handshake succeeded at fallback MTU 23 |
| Physical phone background connection check | App returned Connected after roughly 90 seconds in the background; foreground service remained active with the same process, and a 35-second continuous BLE-event capture recorded no connection changes |
| Physical display pixel output and touch gestures | Not visually or interactively verified |
| Actual Spotify session, Google Maps notification captures, GPS road drive | Not run: require a physical phone/session |
| Alpine wired/wireless Android Auto coexistence | Not run: requires the actual vehicle/phone/head unit |
| GitHub Actions workflow | Added; not executed on GitHub in this session |

Android lint warnings concern optional KTX style changes, newer dependency versions, compatibility constants safely passed through ServiceCompat, and version-specific manifest attributes. Dependencies stay pinned to the tested compatible set. Lint success is not a claim that there are zero warnings.

JUnit coverage comprises navigation parser (10), speed policy (8), speed-limit providers (6), media identity/progress (5), page policy (2), BLE framing/artwork transactions (12), state encoder (8), connection lifecycle cancellation/replacement (2), and Bluetooth address normalization (5). The Maps fixtures are synthetic supported grammars, not captures from a verified Maps release.

On the physical phone, CompanionDeviceManager returned a lowercase address. The original client rejected it before opening GATT, while the foreground service still ran. The corrected client validates the address shape, normalizes with `Locale.ROOT`, and migrates the saved address automatically. The actual connection completed discovery, notification subscription and the protocol hello exchange without deleting the bond. Android returned status 6 for the larger MTU request; the implemented MTU 23 fallback completed the handshake successfully.

Firmware host checks cover page/gesture policy, stale/disconnected GPS, unknown/low-confidence limits, framing/CRC, duplicates, interrupted data, one-time JSON application, invalid UTF-8, excessive JSON nesting and bounded JPEG input/output callbacks. They do **not** execute the ESP32-C6 ROM JPEG decoder on the host. The ROM decoder is linked in the successful C6 cross-build and still requires a device artwork test.

The subsequent device installation preserved the original full flash in a private, Git-ignored `backups/` folder with a checksum, device verification log and restore instructions. Original firmware was `magic8` 0.2.1. Only the new bootloader, partition table and application were written; NVS/PHY ranges were preserved during flashing. First boot performed the expected RF calibration because the original calibration region was empty. Successful initialization logs do not establish visible pixel quality, gesture behavior or a phone connection.

The final application binary is 1,373,904 bytes. The factory flash image is 1,439,440 bytes. The cross-build's static DIRAM usage was 213,660 bytes, leaving 238,452 bytes before runtime allocations. This is a linker report, not a runtime free-heap or largest-contiguous-block measurement. Boot-time and maximum-artwork heap/stack margin remain physical acceptance checks. The JPEG worker falls back to a 120×120 decode if the full 240×240 allocation cannot be satisfied. Use `idf.py size` for the exact current build report.

**Known visual issue:** the existing API 31 emulator (35.1.21) intermittently omitted portions of text after demo/page updates. Some cold launches rendered correctly, but restarting is not a verified workaround; accessibility strings, bounds and controls remained intact. The issue reproduced with hardware acceleration and a temporary software-rendering experiment, and after updating Compose UI/runtime from 1.9.0 to 1.9.5. Its cause remains unresolved; it has not been proven emulator-only. The final app retains normal hardware acceleration and the pinned Compose patch update. Check repeated page changes on a physical phone before accepting the Android UI.

A fresh isolated API 33 emulator using SwiftShader/ANGLE rendered full clock text, all page buttons, footer text and album artwork correctly across music→navigation→driving→music and repeated live updates. No AndroidRuntime errors were logged. This narrows the observed failure to the earlier API 31 environment; it does not establish correctness on every Android 12 device. The scratch emulator was stopped after testing and the existing emulator's settings were preserved.

Reproduce the automated checks using [testing.md](testing.md), [Android setup](android-setup.md) and [hardware setup](hardware-setup.md). Complete the manual acceptance checklist before using the display on the road.
