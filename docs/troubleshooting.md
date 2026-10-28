# Troubleshooting

| Symptom | Check |
| --- | --- |
| Blank AMOLED | Confirm exact C6 2.16 board, USB power, boot logs and PMIC initialization; do not reuse S3/2.06 pinouts. See the CS/IRQ correction in hardware setup. |
| Flash fails | Hold BOOT while reconnecting USB, select the actual USB serial port, release BOOT and retry. Use ESP-IDF 5.5.3 with target esp32c6. |
| No device in association UI | Power board, enable Bluetooth, grant Nearby Devices where required, ensure another phone is not connected, verify service UUID. |
| Paired in phone Bluetooth settings but not connected | Open Car Companion and use Connect a display / Start companion. Pairing alone does not start the app's persistent BLE data connection. |
| Service running but diagnostics say Invalid Bluetooth address | Install the corrected APK. Android companion-device association can return lowercase addresses; the app now normalizes saved and newly selected addresses before Android BLE validation. No bond reset is needed for this issue. |
| Bonding/connection loop | Remove stale phone bond and association, reset display bonds using the documented maintenance method, pair again nearby. Watch Android GATT status and firmware encryption logs. |
| App service stops | Start from the visible app and grant requested permissions. Android force-stop requires user relaunch. OEM battery policies may still terminate the process. |
| Spotify information absent | Enable notification access for Car Companion, start Spotify playback and inspect Media integration status. The app reads only the Spotify session exposed to Android. |
| Artwork absent | Confirm artwork exposed by session, JPEG <=32KiB, MTU/chunk diagnostics, status code and free heap. An absent cover is valid. Progressive JPEG is not supported. |
| Slow artwork at MTU23 | Each packet carries only 8 image bytes; at MTU247 it carries 232. Check the actual negotiated MTU and timed metadata/artwork events in Diagnostics. Obsolete artwork is canceled, song changes take priority, and progress updates are reduced on small links. Larger packets require successful negotiation; the app never assumes 247 after a failed request. |
| GPS speed shows `—` | Grant precise location, start service from app, enable location, test outdoors; stale/low-accuracy fixes intentionally show unavailable. |
| Speed limit shows `—` | Default provider is unknown. Configure your valid road-matching endpoint; check confidence, observation timestamp and units. |
| Navigation missing | Share a destination from Google Maps to Car Companion, confirm it, then tap Start navigation. Check Diagnostics → Google Maps for SDK authorization, routing, or location errors. Starting a trip only in the separate Maps app no longer drives the display. |
| Google rejects the API key | Enable Navigation SDK and billing in the key’s Google Cloud project; check Android package `com.carcompanion.app` and signing SHA-1 restrictions. |
| Shared link does not resolve | Share the place itself or a full Maps URL with destination coordinates/address. Unsupported redirects, country-specific domains, and multi-stop links are rejected. |
| Touch swipes do nothing | Use deliberate horizontal movement; minor/vertical movement is ignored. Check touch coordinates in board bring-up before changing gestures. |
| Old-looking speed after reconnect | Verify firmware build/protocol match and state age; live firmware must age source timestamps and clear disconnected speed. Report if a number persists beyond the documented timeout. |
| Wrong colors in art/panel | Compare panel RGB order, JPEG RGB565 format and DMA byte swapping; use exact board configuration. Do not randomly swap pins. |
| Portions of Android text disappear | Reproduced intermittently on the development API 31 emulator; cause unresolved. See validation results and check the same sequence on your phone. Restarting is not a verified fix. |

Logs intentionally stay bounded and local. Notification diagnostics can include road/destination text. Optional road API calls transmit location only to the configured endpoint. Keep credentials out of issues and source control.
