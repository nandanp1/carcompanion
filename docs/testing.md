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

After an ESP-IDF configure/build has resolved the pinned LVGL component and generated `firmware/build/config/sdkconfig.h`, also run the actual screen layout tests:

```sh
cmake -S firmware/tests -B .cache/firmware-layout-tests -DCOMPANION_TEST_LVGL=ON
cmake --build .cache/firmware-layout-tests -j 4
ctest --test-dir .cache/firmware-layout-tests --output-on-failure
```

These render the production screens with the production fonts. They check long title/artist/album row bounds, ellipsis, embedded line breaks, artwork visibility/CRC behavior and deferred DMA-style flushing. Repeated unchanged long metadata must produce zero extra flushes. Address/undefined sanitizers are enabled by default; Clang's function-pointer type check is excluded for LVGL's existing generic callback convention, while the other checks remain enabled.

## SDK migration acceptance checklist

The notification/Android Auto rows in the historical checklist below describe the previous implementation. For the SDK migration, run these checks on the actual phone and display; passing unit tests does not establish that the key is authorized or that live navigation works.

- Share a real `maps.app.goo.gl` place link and confirm the destination is correct.
- Share coordinates, a place ID link, an address, and an ambiguous place name. Confirm candidate selection and reject unsupported/multi-stop links.
- Deny precise location, then grant it on retry; decline Google’s terms, then retry; test offline and unauthorized-key errors.
- Start guidance with Spotify playing; verify maneuver, distance, road, and remaining time on both phone and display.
- Drive past a turn to trigger rerouting. The old arrow clears until a new step arrives.
- Lock the phone; disconnect/reconnect the display; verify fresh directions and artwork return.
- Share a replacement destination, stop while routing is pending, arrive, and use Stop navigation. No old route should resume.
- Enable demo during a real trip; real navigation stops. Disable demo; no real trip resumes automatically.
- Stop companion and restart; no old guidance should return. Kill the process; a new trip requires another share.
- Revoke Spotify notification access during navigation. Directions remain independent; Spotify reports missing permission.

## Physical acceptance checklist (pre-SDK history)

Perform interactions while parked, then have a passenger observe any driving checks. Record firmware and protocol builds before testing.

| Test | Expected result | Result |
| --- | --- | --- |
| USB cold boot, no phone | Dashboard boots; disconnected state; live speed and limit `—` | Not run |
| Phase 1 demo mode | Clearly labeled synthetic dashboard, navigation and music | Not run |
| Touch calibration | All four corners and center map to expected display positions | Not run |
| Tap and short drag | No page change | Not run |
| Deliberate horizontal swipe | Dashboard left→music; right→navigation, smooth transition | User confirmed faster swipes after update; full directional/threshold checklist pending. |
| Real Maps route starts, demo off | From driving or music, start an actual route; navigation opens once and shows only supported public notification fields | Updated physical phone opened Navigation from saved Driving when it discovered the already-active real route. After firmware installation, user confirmed Navigation opens automatically on the display when asked to end and restart the real route. |
| Manual page override during active route | Swipe to driving/music and wait at least 45 seconds while the route notification remains present; five-second presence checks do not force navigation back into view | Not run |
| Restart after manual selection | Saved page/settings restore as documented | Not run |
| Brightness 5/50/100 percent | Panel responds without flicker; value survives reboot | Not run |
| First association/bond | Correct physical display selected; encrypted data access succeeds | Not run |
| MTU23 and larger negotiated MTU | Same states and image; no fixed chunk-size assumption | Not run |
| 240×240 JPEG | Correct colors/orientation, bounded memory, complete status after decode | Full-scale decode/CRC/attachment and buffer reuse verified across three covers; user confirmed visible covers stay full size. Formal color/orientation check pending. |
| Compact covers on driving and navigation | Both pages show the current album's 52×52 thumbnail with one-line title/artist; speed, maneuver, road and limits remain readable; switching pages reuses the same cover | Production LVGL render assertions and screenshots passed; actual Spotify artwork verified on both physical-phone preview pages. User confirmed covers appear on both physical-display pages after installation. |
| Compact cover during track change or missing artwork | Neither page shows the previous track's cover beside new metadata; text stays centered in the reserved footer until matching artwork arrives, without overlapping the main display | Not run |
| Duplicate/corrupt/missing image packet | Idempotent duplicate, rejection/retry, no reboot | Not run |
| Oversized/progressive/invalid JPEG | Clean failure, no memory overwrite, UI remains responsive | Not run |
| Disconnect halfway through image | Incomplete data released; successful retransfer after reconnect | Not run |
| Reconnect with same track | Full state restored; cached artwork not resent | Not run |
| Rapid track changes | Latest title/art match; no wrong-cover association | Live track callbacks, obsolete-image cancellation and CRC matching exercised; user confirmed faster updates. Longer stress test pending. |
| Long title/artist/album | One line per field with ellipsis; no overlap with controls | Production LVGL host render passed; fixed firmware installed and verified. Dedicated physical long-name visual check pending. |
| Spotify art absent | Placeholder, no cover from prior song | Not run |
| Play/pause/next/previous | Supported session actions occur once each | Not run |
| Real GPS outdoors | Plausible mph, units correct, confidence/age visible in diagnostics | Not run |
| GPS stops/tunnel/location revoked | Speed becomes `—` within three seconds | Not run |
| No speed-limit provider | Limit always `—`, never road-class guess | Not run |
| Stale/low-confidence road API result | Limit rejected; network errors do not crash service | Not run |
| Maps supported notification text | Explicit instruction matches screenshot; only exposed values appear | Actual Maps 26.41.04 notification parsed on Pixel 9 Pro XL: distance-prefixed Keep right instruction, road and arrival time appeared correctly in the preview. Other versions/locales pending. |
| Maps unsupported/localized text | Missing arrow/road stays unknown; diagnostic explains limitation | Not run |
| Stale instruction with confirmed route presence | Keep an active notification present without new posted updates for over 30 seconds; instruction fields become unavailable and diagnostics explain staleness, while route presence and the manually selected page remain unchanged | Not run |
| Route stops/notification removed | End the real Maps route after manually switching pages; navigation becomes inactive and restores the page selected before the route began | Not run |
| Phone locks, app backgrounded | Visible foreground notification; BLE and location continue if permitted | Not run |
| Revoke notification/BLE/location permission | Graceful diagnostics and unavailable data | Not run |
| Bluetooth toggle/device power cycle | Capped reconnect backoff, no overlapping GATT operations | Not run |
| Ignition power with battery removed | Locked phone reconnects to a fully cold-booted display, restores state and retransfers art after the RAM cache is lost | User confirmed reconnection/current song after locking the phone and unplugging/replugging the battery-free display. Post-flash boot also restored encrypted BLE and full-size art automatically. |
| Android service process killed | Enabled automatic recovery restarts BLE only; opening app restores permitted GPS; deliberate Stop stays stopped | Pixel 9 Pro XL process-kill recovery verified: BLE-only foreground service restarted; visible app restored location type. Deliberate Stop covered by regression tests; physical Stop/presence test pending. |
| Android force-stop and relaunch | Explicit start works; no claim to bypass Android force-stop | Not run |
| Wired Android Auto + Spotify + Maps | Audio/navigation uninterrupted while display updates | Not run |
| Wireless Android Auto + Spotify + Maps | Same, record radio/latency behavior for this phone/head unit | Not run |
| One-hour music/art/reconnect soak | No reset; log free heap/largest block, transfer latency and failures | Not run |
| Vehicle power cycle/mount | Secure mount, no obscured view, safe USB supply, clean recovery | Not run |

## Reporting a reproducible failure

Include board SKU/revision, firmware commit/build, Android model/build, app build, protocol/MTU, sequence of steps, whether demo mode is enabled, expected/actual behavior, and relevant bounded logs. Remove location, road names, notification contents, account identifiers and API credentials before sharing. A screenshot of visible diagnostics is sufficient for notification-format support work; never publish a full personal notification dump.
