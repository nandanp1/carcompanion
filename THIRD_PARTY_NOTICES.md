# Third-party notices

The root MIT license covers new Car Companion code. It does not replace upstream licenses, trademarks or service terms.

| Material | Version / provenance | License location |
| --- | --- | --- |
| Waveshare hardware configuration | Official ESP32-C6-Touch-AMOLED-2.16 examples at `294543798f1a44e2f2c4d2976522323f2beee11d` | Board register/pin data transcribed into the project's adapter; upstream source links in hardware setup |
| XPowersLib PMIC library | Vendor example's included copy, retained with source headers | `firmware/components/xpowers/LICENSE` (MIT) |
| Generated Montserrat font glyphs | Montserrat Medium, generated for LVGL | `firmware/licenses/Montserrat-OFL.txt` (SIL Open Font License) |
| ESP-IDF | 5.5.3 | Espressif distribution licenses (primarily Apache-2.0; individual bundled components vary) |
| NimBLE | ESP-IDF's bundled version | Apache-2.0 and upstream bundled notices |
| LVGL | 9.5.0 | Managed component `lvgl__lvgl/LICENCE.txt`; bundled fonts/dependencies have their own notices |
| Espressif SH8601-compatible panel driver | 1.0.0, as used for this CO5300 panel by Waveshare | Managed component's license |
| Waveshare CST9217-compatible touch driver | 1.0.4, as used for this CST9220 by Waveshare | Managed component's license |
| ESP32-C6 ROM TJpgDec | ROM ABI exposed by ESP-IDF 5.5.3 `rom/tjpgd.h` | Espressif ROM header and ChaN TJpgDec copyright notices; bounded project callbacks wrap ROM input/output |
| AndroidX / Jetpack Compose | Pinned in Gradle | Apache-2.0 and package notices |
| Kotlin / coroutines | Pinned in Gradle | Apache-2.0 |
| Gradle wrapper | 9.3.1 | Apache-2.0 |
| Google Navigation SDK | 8.0.0 | [Google Maps Platform terms and SDK policies](https://developers.google.com/maps/documentation/navigation/android-sdk/policies); not covered by MIT; current policy limits use to commercial applications |
| Google Play Services Location | 21.3.0 | Google Android SDK/Play Services terms; not covered by this project's MIT license |

ESP-IDF and Android dependencies are downloaded by their build systems, not relicensed by this repository. Keep all corresponding notices when redistributing binary packages. Review dependency changes before a release, especially if substituting vendor BSP code or fonts.

The official Waveshare example repository does not supply a blanket root license for every example. This project uses independently written board initialization code with cited hardware constants; the directly vendored PMIC library has an explicit MIT license. Do not assume that all files in the upstream examples can be redistributed under this project's license.
