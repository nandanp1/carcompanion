# Navigation integration: verified APIs and experimental formats

Car Companion uses Android's public notification listener, with the owner's explicit Notification access grant. It reads only Google Maps (`com.google.android.apps.maps`) navigation candidates. It does not use AccessibilityService, Android Auto private APIs, audio capture, screen capture, or Google Maps internal storage.

## What is verified

Android documents notification listener callbacks, active notification enumeration, and removal notifications. A notification may expose public title, text, expanded text, subtitle, and a navigation category. These are Android features, **not a Google Maps navigation-data API**:

- [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
- [Notification fields and categories](https://developer.android.com/reference/android/app/Notification)
- [MediaSessionManager access through an enabled notification listener](https://developer.android.com/reference/android/media/session/MediaSessionManager#getActiveSessions(android.content.ComponentName))

The Google Navigation SDK is an integration for navigation owned by the integrating application. It does not expose another installed Google Maps application's active route. It also has its own setup, terms, and billing: [Navigation SDK overview](https://developers.google.com/maps/documentation/navigation/android-sdk/overview).

A real ongoing Google Maps notification was inspected on a Pixel 9 Pro XL / Android 17 with Maps 26.41.04.993332563. It used the `navigation` category and `Notification.ProgressStyle`, with a combined title shaped like `350\u00a0ft · Keep right to stay on Sample Rd`, empty text, and a subtitle shaped like `Arrive 1:31 PM`. The street is replaced here to avoid publishing location data. Regression fixtures preserve that observed structure with synthetic road names. Other grammar fixtures remain synthetic; this observation does not establish support for every Maps version or locale.

## Supported notification text

The parser requires an ongoing Google Maps notification with Android's navigation category, or both an explicit recognized instruction and a maneuver distance. Supported English instruction prefixes include `Turn left`, `Turn right`, `Turn slight left/right`, `Keep left/right`, `Continue on/straight`, cardinal `Head` directions, `Make a U-turn`, and explicit roundabout instructions. An optional `In 500 ft,` prefix is supported. The observed distance-plus-middle-dot title prefix is also supported; Unicode spacing such as nonbreaking spaces is normalized before parsing. Only a complete recognized distance may be stripped before the middle dot.

The maneuver is extracted exclusively from the instruction phrase. Words in a road name never determine direction. A street is shown only when an instruction explicitly uses `onto`, `on`, or `toward`. Distances recognize miles, feet, kilometers, and meters. A supplied time or duration can be retained; arrival times and traffic estimates are never calculated from incomplete notification text.

A category-only notification may activate the navigation page but shows unavailable instruction fields. An arrow represented only by an icon is **not** interpreted. Unsupported locales, custom layouts, redacted notifications, OEM differences, and future Maps formatting can therefore show no turn arrow. No speed limit is read or inferred from Maps notifications.

A detected active route automatically opens Navigation on the phone preview and display. Later updates preserve a manual page choice; confirmed route end restores the selection from before the route. While the companion service runs, the listener checks active Maps notifications every five seconds. Route presence and instruction age are separate: an unchanged notification keeps the route active, while maneuver/distance/road/ETA older than 30 seconds become unavailable. Notification removal gets a 250 ms grace period to reconcile replacement notifications before ending the route. If presence cannot be confirmed for 30 seconds or notification access disconnects, navigation ends. The diagnostics screen distinguishes a present route with stale instructions from missing route evidence.

## On-device diagnosis

1. Grant Notification access in Android settings and start Car Companion while parked.
2. Start a real Google Maps route. Open Car Companion's Maps diagnostics.
3. Check the captured category, public title/text, available extra names, and parser result. History is limited to 12 Maps notifications in memory and is not uploaded or persisted.
4. Verify the direction, road, and distance against the phone for several maneuvers. Repeat with Android Auto active and for each Maps update or locale.
5. If text does not contain a reliable instruction, keep the dashboard/music view or use an app-owned navigation integration. Do not add speculative mappings based on icons or destinations.

## Functional alternatives

`MockNavigationProvider` emits clearly labeled synthetic routes for bench testing. The foreground service's **Demo** control exercises route activation, changes, end, and page restoration. Both phone and device mark simulated data as DEMO; disable this before driving.

`NavigationProvider` exposes a `StateFlow<NavigationState>`. `StructuredNavigationProvider.submit()` is a validated in-process integration interface for an authorized navigation SDK that owns its route. To integrate it, collect that provider in `CompanionService` where `MapsNotificationListener.updates` is currently collected. Supply monotonic `updatedAtElapsedMs` on each update and `active=false` at route end. This is an integration seam, not a preconfigured third-party navigation subscription or an exported unauthenticated broadcast receiver.

## Android Auto coexistence

Media session observation and navigation notification observation do not request audio focus or alter audio routing. BLE uses the custom GATT service only. The app is designed to coexist with wired or wireless Android Auto; concurrent RF performance, OEM power management, Maps notification contents, and Alpine behavior require the manual hardware checklist. No Android Auto compatibility certification or physical coexistence test is claimed.
