# Testing

Automated results and physical test results are separate. The actual commands run in this development environment and their results are recorded in [validation.md](validation.md). No connected ESP32, physical GPS drive, Spotify account session, or Alpine head unit is assumed.

## Host and build checks

From the repository root:

```sh
python3 -m unittest discover -s protocol -p 'test_*.py' -v
python3 tools/test_firmware_protocol.py
cmake -S firmware/tests -B firmware/tests/build
cmake --build firmware/tests/build
ctest --test-dir firmware/tests/build --output-on-failure
```

The reference suite covers negotiated MTUs 23/64/185/247/517, UTF-8 byte fragmentation, bounds and overflow, invalid flags, exact and corrupted duplicates, gaps, interrupted transactions, CRC, cached images and decode failures. It is an independent conformance implementation. The differential test compiles the actual firmware parser with AddressSanitizer and UndefinedBehaviorSanitizer and compares 19,440 operations against that oracle. Firmware and Android production code also have their own behavior test targets.

Android:

```sh
cd android
./gradlew testDebugUnitTest assembleDebug lintDebug
```

Firmware host tests and ESP-IDF commands are in [hardware setup](hardware-setup.md). Unit tests cannot validate DMA alignment, real touch coordinates, BLE radio scheduling, pairing UI, Android process lifetime or vehicle installation.

## Physical acceptance checklist

Perform interactions while parked, then have a passenger observe any driving checks. Record firmware and protocol builds before testing.

| Test | Expected result | Result |
| --- | --- | --- |
| USB cold boot, no phone | Dashboard boots; disconnected state; live speed and limit `—` | Not run |
| Phase 1 demo mode | Clearly labeled synthetic dashboard, navigation and music | Not run |
| Touch calibration | All four corners and center map to expected display positions | Not run |
| Tap and short drag | No page change | Not run |
| Deliberate horizontal swipe | Dashboard left→music; right→navigation, smooth transition | User confirmed faster swipes after update; full directional/threshold checklist pending. |
| Route starts/ends | Opens navigation once, manual override works, returns to previous selection | Not run |
| Restart after manual selection | Saved page/settings restore as documented | Not run |
| Brightness 5/50/100 percent | Panel responds without flicker; value survives reboot | Not run |
| First association/bond | Correct physical display selected; encrypted data access succeeds | Not run |
| MTU23 and larger negotiated MTU | Same states and image; no fixed chunk-size assumption | Not run |
| 240×240 JPEG | Correct colors/orientation, bounded memory, complete status after decode | Full-scale decode/CRC/attachment and buffer reuse verified across three covers; user confirmed visible covers stay full size. Formal color/orientation check pending. |
| Duplicate/corrupt/missing image packet | Idempotent duplicate, rejection/retry, no reboot | Not run |
| Oversized/progressive/invalid JPEG | Clean failure, no memory overwrite, UI remains responsive | Not run |
| Disconnect halfway through image | Incomplete data released; successful retransfer after reconnect | Not run |
| Reconnect with same track | Full state restored; cached artwork not resent | Not run |
| Rapid track changes | Latest title/art match; no wrong-cover association | Live track callbacks, obsolete-image cancellation and CRC matching exercised; user confirmed faster updates. Longer stress test pending. |
| Spotify art absent | Placeholder, no cover from prior song | Not run |
| Play/pause/next/previous | Supported session actions occur once each | Not run |
| Real GPS outdoors | Plausible mph, units correct, confidence/age visible in diagnostics | Not run |
| GPS stops/tunnel/location revoked | Speed becomes `—` within three seconds | Not run |
| No speed-limit provider | Limit always `—`, never road-class guess | Not run |
| Stale/low-confidence road API result | Limit rejected; network errors do not crash service | Not run |
| Maps supported notification text | Explicit instruction matches screenshot; only exposed values appear | Not run |
| Maps unsupported/localized text | Missing arrow/road stays unknown; diagnostic explains limitation | Not run |
| Route stops/notification removed | Navigation stops and restores prior page | Not run |
| Phone locks, app backgrounded | Visible foreground notification; BLE and location continue if permitted | Not run |
| Revoke notification/BLE/location permission | Graceful diagnostics and unavailable data | Not run |
| Bluetooth toggle/device power cycle | Capped reconnect backoff, no overlapping GATT operations | Not run |
| Android force-stop and relaunch | Explicit start works; no claim to bypass Android force-stop | Not run |
| Wired Android Auto + Spotify + Maps | Audio/navigation uninterrupted while display updates | Not run |
| Wireless Android Auto + Spotify + Maps | Same, record radio/latency behavior for this phone/head unit | Not run |
| One-hour music/art/reconnect soak | No reset; log free heap/largest block, transfer latency and failures | Not run |
| Vehicle power cycle/mount | Secure mount, no obscured view, safe USB supply, clean recovery | Not run |

## Reporting a reproducible failure

Include board SKU/revision, firmware commit/build, Android model/build, app build, protocol/MTU, sequence of steps, whether demo mode is enabled, expected/actual behavior, and relevant bounded logs. Remove location, road names, notification contents, account identifiers and API credentials before sharing. A screenshot of visible diagnostics is sufficient for notification-format support work; never publish a full personal notification dump.
