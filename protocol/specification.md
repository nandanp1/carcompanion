# Car Companion BLE protocol 1.0

All integers are unsigned little-endian unless explicitly stated. The display is a BLE peripheral, never an audio endpoint. The phone is the sole central. Protocol version 1 is intentionally small enough to work at ATT MTU 23. Characteristic UUIDs share suffix `-5a7e-4b7d-9c2a-7b986c0a0000`.

| UUID prefix | Role | Properties |
| --- | --- | --- |
| `98600000` | Primary service | — |
| `98600001` | Control | write with response, read, notify |
| `98600002` | Navigation | write with response |
| `98600003` | Driving | write with response |
| `98600004` | Music | write with response |
| `98600005` | Artwork | write with response |
| `98600006` | Playback | notify |

Device name: `Car Companion`. Discover by service UUID, not name. CCCD subscriptions to control and playback must finish before hello. Only one ATT operation may be outstanding. Disconnect and retry if an operation times out; old GATT callbacks must not complete a new connection's work.

## Phone-to-display frame

Each write contains a 12-byte envelope followed by bytes. A write must fit `min(negotiated MTU - 3, 512)`; payload capacity is this value minus 12. Initial MTU is 23. Request 247, use the actual negotiated result (Android may request 517). No zero-length fragments.

| Byte | Type | Meaning |
| --- | --- | --- |
| 0 | u8 | version = 1 |
| 1 | u8 | flags: START = 1, END = 2; no other bits |
| 2 | u16 | transfer/message ID, 1–65535 |
| 4 | u32 | byte offset in logical message |
| 8 | u32 | total bytes in logical message |
| 12 | bytes | fragment data |

START must be set exactly when offset is zero. END must be set exactly when offset + payload size equals total. Reject overflow, overlong, unknown version/flags, gaps, changing IDs/totals mid-message and inconsistent duplicates. An exact duplicate of already accepted bytes is idempotent. For a new JSON message, START replaces the incomplete message on that characteristic. Each JSON characteristic has an independent reassembly slot, bounded to 1024 bytes. Parse UTF-8 JSON only once END completes the message. Lengths are bytes, not character counts. Unknown JSON keys are ignored; wrong field types and invalid ranges are rejected. No raw Kotlin/C struct serialization.

Artwork uses this same envelope but a separate bounded receiver, maximum 32768 compressed bytes, and requires art_begin first. Its frame ID must match art_begin.id. Start/end flags do not allocate additional buffers. One artwork transfer at a time.

## Control notification and read: 16 bytes (unframed)

| Byte | Type | Meaning |
| --- | --- | --- |
| 0 | u8 | protocol major = 1 |
| 1 | u8 | event: 1 hello, 2 artwork status, 3 protocol error |
| 2 | u8 | status code below |
| 3 | u8 | protocol minor = 0 |
| 4 | u16 | relevant artwork/message ID, or zero |
| 6 | u16 | maximum artwork size in KiB = 32 |
| 8 | u32 | expected next artwork byte offset; for hello, firmware build = 1 (0.1.0) |
| 12 | u32 | CRC32 of currently decoded/cached image, or zero if none |

Status codes: 0 OK, 1 READY, 2 COMPLETE, 3 CACHED, 4 BAD_OFFSET, 5 BAD_CRC, 6 INVALID, 7 BUSY, 8 DECODE_FAILED. Read always returns a hello snapshot. Zero CRC is the cache-empty sentinel; a zero-CRC image is retransferred normally. Firmware only advertises a cached CRC after successful JPEG decode. Subscribe before commands so status notifications cannot be missed. Phone installs an ID-filtered status waiter before writing begin/commit. Missing status times out, then retries the transaction (up to 3 attempts); disconnect clears all pending waiters.

## JSON messages

All examples are logical payloads, fragmented as necessary. Nullable values are literal JSON null. The receiver must never substitute a default numerical speed or limit for missing information.

### Control

* `{"op":"hello"}` → hello status; phone restores all current state after hello.
* `{"op":"brightness","value":70}` — integer 5–100 percent, persisted locally.
* `{"op":"clock","unix":1792252800,"offset_min":-420}` — UTC Unix seconds plus phone local UTC offset; refreshed periodically. Monotonic time governs data age.
* `{"op":"demo","enabled":true}` — explicit simulation, visibly marked DEMO. Disable before live use.
* `{"op":"art_begin","id":42,"size":12345,"crc":305419896}` — allocates at most 32768 bytes. Respond READY offset 0, or CACHED if a decoded image has identical CRC. An identical active begin resumes at the reported offset; a different begin replaces the incomplete transfer when the decoder is idle. BUSY if a completed image is currently decoding.
* `{"op":"art_commit","id":42}` — requires all bytes, checks CRC then decodes outside the NimBLE/display callback. COMPLETE only after successful decode. BAD_OFFSET contains current offset. BAD_CRC discards the failed transfer while preserving the previous valid image. Decoding releases the previous RGB buffer to fit C6 RAM; DECODE_FAILED can therefore leave the cache empty and artwork hidden.
* `{"op":"art_abort","id":42}` — releases incomplete transfer if ID matches.

### Driving

`{"speed_mph":42.3,"gps_age_ms":120,"accuracy_m":6.0,"limit_mph":null,"limit_confidence":0.0,"limit_age_ms":null,"limit_source":"unavailable"}`

Speed must be finite, 0–300 mph, derived from an Android location with speed and acceptable accuracy. Phone suppresses locations older than 3000ms or horizontal accuracy over 50m. Firmware additionally ages speed from packet receipt and displays an em dash when source age + elapsed >= 3000ms or disconnected. Limit is a distinct road-matched observation; null if absent, confidence <0.8, or age >=30000ms. Limit confidence 0–1, limit 1–200 mph. Firmware enforces these bounds and age limits again. A mock limit is only sent with explicit demo mode.

### Navigation

`{"active":true,"maneuver":"left","distance":"500 ft","road":"Market Street","eta":null,"reliable":true,"source":"maps_notification"}`

Maneuver enum: unknown, straight, left, right, slight_left, slight_right, uturn, roundabout, arrive. Strings max 96 UTF-8 bytes; eta max 32. Never infer an arrow from a distance or icon. For incomplete notification information use maneuver unknown, null distance/road/eta, reliable false. Reliable means the parser recognized a supported explicit textual instruction, not that Google provides a stable API. Active navigation with incomplete information may show its source text in diagnostics, never a fabricated arrow. Send inactive on removal or stale source timeout. Navigation auto-selects on inactive→active, stores previous manual page and restores it on active→inactive. Swiping manually overrides the page until the next route starts.

### Music

`{"track_id":"spotify:track:example","title":"Song","artist":"Artist","album":"Album","duration_ms":180000,"position_ms":12000,"playing":true,"art_crc":305419896}`

Each string max 128 UTF-8 bytes. Duration/position are nonnegative, bounded to 86400000ms; position <= duration when duration known. Missing artwork is art_crc null; hide stale artwork if CRC does not match current song. Track ID must change when relevant metadata changes even if a session omits its media ID (hash title/artist/album). Empty session clears metadata. Progress uses monotonic elapsed time while playing and clamps to duration. Metadata is sent ahead of a new image so the old song's image is not mislabeled.

## Artwork transaction and recovery

Resize to 240×240 (baseline JPEG, RGB), configurable initial quality default 75. Reduce quality and, if necessary, dimensions to 120×120 until <=32768 bytes. CRC is IEEE CRC-32 / ISO-HDLC: polynomial 0xEDB88320, initial 0xffffffff, final xor 0xffffffff; `123456789` → 0xcbf43926. Use CRC of compressed bytes as cache key. No artwork URI authentication or Spotify Web API is required.

Begin → READY/CACHED → sequential artwork writes with response → commit → COMPLETE. ATT responses provide one-packet flow control; no write-without-response flooding. Firmware appends bytes before acknowledging the write. Out-of-order writes are rejected with ATT error and BAD_OFFSET notification. Exact duplicates succeed. CRC/decode failure retries from begin up to 3 times. If a track changes, cancel the obsolete transaction after its outstanding ATT operation completes, then send the latest metadata and artwork only. The next art_begin replaces an incomplete image; a decoder already running may return BUSY and is retried normally. JSON state updates may interleave between artwork packets; they have independent receivers. Disconnect discards incomplete compressed data, retaining only the last successfully decoded image. Reconnect hello advertises cached CRC and phone skips matching artwork; otherwise begins a new transfer. No transfer bandwidth or completion-time guarantee.

The Android sender gives track, artwork-identity and playback changes priority over driving fragments and artwork chunks. A bounded queue and one cancellation-safe ATT operation gate preserve serialization. Canceled work cannot release its operation slot before the matching callback or timeout. A music message that becomes obsolete is abandoned between fragments and replaced with a new START frame. Progress-only resynchronization uses a 5-second interval below MTU 64, 3 seconds below MTU 185, and 1 second otherwise; the display interpolates progress locally. Track changes, play/pause and seeks bypass that cadence. Android requests high connection priority during metadata/artwork bursts and restores balanced priority afterward; the central and radio conditions determine actual timing.

Firmware JPEG work runs outside the BLE host and LVGL thread. One 32768-byte compressed buffer and one 240×240×2 RGB565 output buffer maximum, plus decoder scratch. Validate JPEG dimensions before output allocation. Never allocate a full 480×480 framebuffer. Failure to allocate produces a status rather than reboot. Keep current decoded image until replacement is ready only if memory permits; otherwise hide/free it before decoding and clear cached CRC. LVGL image pointers must only be replaced/freed under its owning thread/lock.

## Playback notification: 4 bytes (unframed)

`[1, command, sequenceLo, sequenceHi]`, command 1 toggle play/pause, 2 next, 3 previous. Phone ignores repeated sequence within a connection and resets deduplication at reconnect. It invokes transport controls only on the active Spotify MediaSession, checking available action bits. No audio profile, focus request, or media routing changes.

## Connection and security

User explicitly associates/selects a nearby device while parked. Request OS bonding; firmware enables encrypted characteristic access and stores bonds in NVS. No-input/no-output pairing has no MITM protection: verify the physical device and pair in a trusted setting. A display may have one active connection. Reconnect uses 1, 2, 4, 8, 16, 30 second capped delays with jitter. Foreground service is started from visible user action with required Bluetooth/location permissions; Android can still stop it. No hidden permission bypass. Re-association/reset bond is a deliberate user maintenance action.

## Versioning

Different major versions must not exchange data. Additive JSON keys and new optional enum values are minor changes. Unknown maneuvers render unknown. This initial firmware reports build 1 = 0.1.0. Protocol and implementation are integration-tested locally; physical RF, panel, notification formats, and Android Auto coexistence require the manual checklist.
