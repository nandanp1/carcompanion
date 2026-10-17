# Speed and speed-limit providers

Current speed comes from Android's Fused Location Provider, converted from meters per second to mph. It is never derived from a posted speed limit, accelerometer or guessed road class. Missing speed, mock locations in live mode, poor accuracy and fixes older than three seconds render as `—`.

The default `UnknownSpeedLimitProvider` makes no network requests and always returns unknown. This is the expected production setting until a valid road-data source is configured. Google Maps' visible speed-limit indicator is not a third-party data API.

`SpeedLimitProvider.lookup(RoadFix)` is the substitution point for licensed road data. `MockSpeedLimitProvider` produces synthetic data only in explicitly labeled demo mode. `CameraSpeedLimitProvider` accepts a future `SpeedSignRecognitionSource`; no camera classifier or camera permission is bundled. Until a validated observation source is supplied, that integration cannot produce a speed limit.

The app includes a functional `RoadMatchedSpeedLimitProvider` HTTP adapter for a user-operated HTTPS service. It is not a bundled road database, commercial vendor subscription or promise of coverage. Configure the endpoint in the app. No location is sent to it until selected. The service must map the supplied fix to a road segment and return the segment's posted limit with confidence and observation time.

Request, at most approximately once per ten seconds while the location service is running:

```http
GET /speed-limit?lat=37.0&lon=-122.0&accuracy_m=6.0&bearing=90.0
Accept: application/json
Authorization: Bearer <optional token>
```

Response, limited to 8 KiB:

```json
{
  "roadMatched": true,
  "mph": 35,
  "confidence": 0.96,
  "observedAtEpochMs": 1792252800000,
  "roadId": "provider-specific-road-segment"
}
```

This is an example schema, not a live observation. Return `roadMatched:false` when ambiguous. `mph` must be an integer from 1–200, confidence 0.8–1, and timestamp less than 30 seconds old and not in the future. Use the observation time, never a permanently refreshed cached database timestamp. The app rejects untrusted, stale and malformed responses; the display ages the value again. API failures render unknown. Requests use HTTPS, refuse redirects and have connection/read timeouts.

The endpoint operator must supply road coverage, data licensing/attribution, key issuance, billing and rate limits. None are provided by this repository. Dense parallel roads, tunnels, ramps, school zones, time-dependent limits, temporary construction signs, lane-specific limits and low GPS accuracy can invalidate a match. Return unknown rather than choosing the nearest road arbitrarily. The physical sign and vehicle instruments remain the driver's reference.

The BLE payload explicitly separates `speed_mph`, `gps_age_ms`, `accuracy_m`, `limit_mph`, `limit_confidence`, `limit_age_ms` and `limit_source`. Diagnostics expose provider status. A valid posted limit is not an instruction to drive at that speed.

Source: [Fused Location Provider](https://developers.google.com/location-context/fused-location-provider) and [Android Location speed/accuracy APIs](https://developer.android.com/reference/android/location/Location).
