# Waveshare hardware and firmware setup

This target is **ESP32-C6-Touch-AMOLED-2.16**, including its `-EN` variant. Do not select the 1.43, 1.8, 2.06, or ESP32-S3 board definitions. Firmware requires no Raspberry Pi, Wi-Fi, SD card, phone audio connection, or Spotify account credentials. Its only radio role is a BLE peripheral.

## Verified manufacturer sources

The [official product documentation](https://docs.waveshare.com/ESP32-C6-Touch-AMOLED-2.16) identifies a 160MHz ESP32-C6, 512KiB HP SRAM and 16KiB LP SRAM, 16MiB flash, a 480×480 CO5300 QSPI AMOLED, CST9220 I²C capacitive touch, QMI8658 IMU, PCF85063 RTC, AXP2101 PMIC, TF/microSD slot, ES8311 audio codec, ES7210 ADC and microphones. There is **no PSRAM**. These resources are present on the board; this application initializes the PMIC, display, touch, physical buttons, ES8311 speaker output and BLE. The microphones, IMU, RTC and SD are unused.

Initialization was checked against [Waveshare's official repository](https://github.com/waveshareteam/ESP32-C6-Touch-AMOLED-2.16), commit `294543798f1a44e2f2c4d2976522323f2beee11d`:

- `02_Example/ESP-IDF-v5.5.3/09_LVGL_V9_Test/components/port_bsp/display_bsp.cpp`: panel commands, QSPI frequency, rotation, touch orientation, PMIC reset sequence.
- `02_Example/ESP-IDF-v5.5.3/09_LVGL_V9_Test/components/port_bsp/display_bsp.h` and `main/main.cpp`: actual constructor pin defaults.
- `02_Example/Arduino-v3.3.3/09_LVGL_V9_Test/user_config.h`: matching explicit pin definitions.
- `02_Example/ESP-IDF-v5.5.3/09_LVGL_V9_Test/main/idf_component.yml`: manufacturer-selected driver families.

The CO5300 is initialized through Espressif's `esp_lcd_sh8601` driver with the manufacturer's exact CO5300 register table. The CST9220 uses Waveshare's `esp_lcd_touch_cst9217` component, as selected by their example. Those package names do **not** imply that this board contains an SH8601 or CST9217. The actual controllers are CO5300 and CST9220.

## Pin map

| Function | GPIO / connection |
| --- | --- |
| Shared I²C SCL / SDA | 7 / 8 |
| AMOLED QSPI clock | 0 |
| AMOLED QSPI D0 / D1 / D2 / D3 | 1 / 2 / 3 / 4 |
| AMOLED chip select | **15** |
| AMOLED reset | PMIC ALDO3 power sequence; no reset GPIO |
| AMOLED brightness | CO5300 register `0x51`; no PWM backlight GPIO |
| Touch interrupt | **5** |
| Touch reset | 11 |
| AXP2101 I²C address | `0x34` |
| Touch orientation | swap XY, mirror Y |
| BOOT / KEY buttons | 9 / 10, active low |
| PWR short press | AXP2101 short-press IRQ, register `0x49` bit 3 |
| ES8311 I²C address | `0x18` (7-bit) |
| Speaker I²S MCLK / BCLK / WS / DOUT | 19 / 20 / 22 / 23 |

**Manufacturer example inconsistency:** the IDF example's `main/user_config.h` swaps LCD CS and touch interrupt (CS5/IRQ15), but those constants are not passed to its actual `DisplayPort` constructor. Its running initialization uses the constructor defaults **CS15/IRQ5**, matching the official Arduino example. This project uses those actual values explicitly. See [manufacturer schematic](https://files.waveshare.com/wiki/ESP32-C6-Touch-AMOLED-2.16/ESP32-C6-Touch-AMOLED-2.16-Schematic.pdf) when checking board revisions. Do not “correct” the project to the unused swapped header values.

The panel runs 40MHz QSPI, RGB565, partial rendering with two 480×10×2-byte DMA stripes (19,200 bytes total), and even-aligned flush rectangles. Rendering can overlap the previous stripe's DMA transfer. A FreeRTOS semaphore waits for DMA completion instead of spinning on the C6's single core. The supplied vendor display commands set a 480×480 address window and rotation `0x30`. The PMIC supplies 3.3V rails; ALDO3 is toggled with the manufacturer's 100ms delays to reset the display. Battery charger parameters remain at factory settings.

## Build

Install [ESP-IDF](https://docs.espressif.com/projects/esp-idf/en/v5.5.3/esp32c6/get-started/index.html) **v5.5.3**, the version of the manufacturer example. A normal installation is:

```sh
mkdir -p ~/esp
git clone --branch v5.5.3 --recursive https://github.com/espressif/esp-idf.git ~/esp/esp-idf-v5.5.3
cd ~/esp/esp-idf-v5.5.3
./install.sh esp32c6
. ./export.sh
cd /path/to/carcompanion/firmware
idf.py set-target esp32c6
idf.py build
```

The component manager downloads exact versions from `src/idf_component.yml`: esp_codec_dev 1.4.0, LVGL 9.5.0, `esp_lcd_sh8601` 1.0.0, and `esp_lcd_touch_cst9217` 1.0.4. `dependencies.lock` pins their resolved transitive components too. JPEG uses the ESP32-C6 ROM TJpgDec exposed by IDF's `rom/tjpgd.h`, also used internally by Espressif's `esp_jpeg` component. Our own callbacks bound both reads and skips and check output rectangles before RGB888→RGB565 conversion; the decoder uses 3,100 bytes of scratch. XPowersLib 0.3.3 is vendored with its MIT license. Generated speed/dash fonts retain the Montserrat SIL Open Font License under `licenses/`.

For bench simulation, run `idf.py menuconfig`, choose **Car Companion → Show clearly labeled simulated driving/music/navigation data**, save, rebuild and flash. The build default is OFF. The Android app can also explicitly enable/disable demo mode over BLE. Autonomous mock speed and navigation run only while disconnected; while connected, the phone's shared preview state supplies the demo. Demo screens are visibly labeled and do not invent a speed limit.

## Flash and pair

1. Connect a data-capable USB-C cable to the board. Use the board's USB serial/JTAG port; list ports with `python -m serial.tools.list_ports`.
2. Run `idf.py -p /dev/cu.usbmodemXXXX flash monitor` on macOS (or the appropriate `COM` / `/dev/ttyACM` port). Exit the serial monitor with Ctrl+].
3. If download mode fails, hold **BOOT**, reconnect USB power, release BOOT, then retry. Do not erase flash routinely: NVS stores settings and bonds.
4. Confirm the black driving dashboard shows an em dash for speed before a valid phone fix. Swipe left to music; swipe right from driving to navigation. A swipe must travel at least 70px, be at least twice as horizontal as vertical, and finish within 900ms.
5. In the Android app, grant the requested Bluetooth, location, notification and notification-listener access, then associate/select **Car Companion** while parked. The device advertises its custom UUID and supports one encrypted BLE connection.
6. Start the visible companion service from the app. When GPS is available, the dashboard should update; disconnect or stop location updates and verify the speed changes to `—` within 3 seconds.

For a single-file **initial factory flash**, `idf.py merge-bin -o car_companion-flash.bin` creates `build/car_companion-flash.bin`, flashed at address `0x0`. This merged image also overwrites the NVS gap, resetting bonds and settings. Use the normal `idf.py flash` command above for routine updates that preserve NVS.

If deliberately replacing the trusted phone, remove its bond in Android settings and run `idf.py -p PORT erase-flash`, then flash again. This resets brightness and page settings too. Firmware refuses to silently replace an existing bond on a repeated pairing request. Secure Connections without input/output offers encryption but no MITM protection; verify the nearby physical display during first pairing.

## Host tests without a board

From the repository root, compile the exact production framing, display freshness/page policy and JPEG callback code with a host C++17 compiler:

```sh
cmake -S firmware/tests -B .cache/firmware-host-tests
cmake --build .cache/firmware-host-tests
ctest --test-dir .cache/firmware-host-tests --output-on-failure
python3 tools/test_firmware_protocol.py
```

If macOS CMake stalls while discovering Xcode through `xcrun`, explicitly set `-DCMAKE_CXX_COMPILER=/usr/bin/clang++`. A direct sanitizer run is:

```sh
clang++ -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined \
  -Ifirmware/include firmware/tests/state_tests.cpp firmware/src/Protocol.cpp \
  -o /tmp/car-companion-state-tests
/tmp/car-companion-state-tests
```

These check swipe thresholds, navigation restoration/manual override, missing limits, disconnected and stale GPS, CRC, duplicates, interrupted transfers, bounded JPEG reads/skips/output rectangles, excessive JSON nesting and malformed UTF-8. Duplicate completed fragments are acknowledged without parsing/applying the same JSON again, so an old GPS packet cannot refresh its age. Host tests do not execute ROM JPEG decoding, the panel, real touchscreen hardware or BLE RF.

## Memory and task design

The BLE host validates and copies one ATT write at a time, then acknowledges it. Four independent JSON slots are limited to 1,024 bytes each. A single artwork slot holds at most 32,768 compressed bytes. JPEG CRC and decoding run in the `jpeg_decode` FreeRTOS task, separate from NimBLE and LVGL. The decoder checks dimensions before allocating an output; both width and height must be ≤240. Output is at most 115,200 bytes of RGB565. No full 480×480 framebuffer is allocated.

There is only one decoded image, shared by all three screens. Driving and Navigation use 52×52 image widgets that scale this same descriptor into the existing draw stripes; Music retains its full-size cover. No second decoded image or thumbnail transfer is needed. All screens hide mismatched or unavailable artwork. After a new image passes CRC and header validation, the old image is detached from every screen under the display mutex and its move-only buffer ownership passes to the decoder. Sufficient pixel capacity is reused across tracks, avoiding repeated allocations and heap fragmentation. JPEG decoding runs without the display mutex, and successful output ownership returns to the display. Growth releases the old allocation before requesting a larger one, preserving the one-buffer bound. If the full RGB565 allocation fails, the ROM decoder tries native half-scale output (240×240 becomes 120×120, 28,800 bytes), without an intermediate image. If that allocation or decoding fails, the screen uses a placeholder and clears the cache key. CRC or invalid-header failures preserve the previous image. Cache CRC is advertised only after successful decode; a legitimate zero CRC is displayed but not treated as a cache hit. This bounded strategy favors responsiveness on the C6 over retaining two artwork images in memory.

New state, artwork and completed swipe gestures wake the display task immediately; the LVGL refresh and touch timers target 16ms. Independent 100ms updates keep playback progress and data-age checks moving even when the phone sends nothing. Animations are 180ms. The 16ms timer is a scheduling target, not a measured 60fps claim: 40MHz quad-SPI needs at least 23.04ms for a full 480×480 RGB565 frame before command and rendering overhead. Partial updates can be faster. This project retains the manufacturer's verified display bus clock. Brightness is clamped to 5–100% and persisted in NVS. The last manual page persists; navigation activation temporarily selects navigation and route termination restores the page selected before that route. Manual selection remains possible during navigation.

The earlier performance/diagnostic build was cross-compiled successfully with ESP-IDF v5.5.3: application binary 1,380,816 bytes, static DIRAM 213,832 bytes, and 238,280 bytes of linker-reported DIRAM remaining **before runtime allocations**. This is not a measurement of free heap on running hardware. The sanitizer suite and 19,440 production framing/oracle comparisons passed. Timing changes and radio coexistence still require the manual hardware checklist.

### Performance and artwork diagnostics

The serial log records actual ATT MTU, connection interval, encryption state and link-layer payload. The firmware advertises MTU 247 and requests a 251-byte link-layer payload using the same values as [Espressif's NimBLE throughput example](https://github.com/espressif/esp-idf/blob/v5.5.3/examples/bluetooth/nimble/throughput_app/bleprph_throughput/main/main.c); the phone controls the final negotiation. MTU 23 carries only 8 application bytes per acknowledged fragment after the ATT and project headers, compared with 232 bytes at MTU 247. A low MTU can dominate artwork-transfer time irrespective of panel refresh.

`Artwork` logs identify begin, bounded progress, commit, CRC rejection, JPEG prepare/decode result, output dimensions, transfer/decode durations, largest free heap block and decoder task stack margin. `Music` logs distinguish a decoded image from a displayed image: visibility requires the latest music metadata's artwork CRC to match the decoded CRC. This prevents an old cover from appearing with a new track. A completed transfer followed by `art visible=0` with mismatched CRCs points to metadata ordering or a superseded track, rather than an incomplete JPEG transfer. `source_accepted=0` indicates LVGL rejected the image descriptor. No song titles or artist names are included in these firmware diagnostics.

Every five seconds with rendered changes, `Display` reports render count, average/max render duration and free/largest heap. Counts describe actual invalidated redraws, not the AMOLED controller's independent scan rate. Static screens deliberately redraw less often.

## Mounting and road test

Use a regulated automotive USB supply, secure the enclosure and cable, and keep the display clear of the driver's sight lines, mirror adjustment and airbags. This is a secondary informational display: retain the vehicle's original instruments. GPS is not a vehicle speed sensor and can be absent or delayed. Avoid placing a lithium battery in a hot windshield-mounted enclosure; the manufacturer offers the `-EN` version without an included battery. Test stationary before a passenger conducts road validation.

Validate both wired Android Auto + BLE and wireless Android Auto + BLE with your actual Android phone and Alpine unit. The firmware exposes no Bluetooth audio profile, and the Android app requests no audio routing or focus change. Coexistence still requires physical radio and OEM background-service testing. The repository's automated builds cannot certify those physical behaviors.

## Navigation speaker and physical buttons (0.3.0)

With the screen facing you, BOOT lowers navigation volume and KEY raises it, in 5% steps. Holding a button repeats the adjustment. A ten-bar overlay shows the level; zero mutes. Volume is saved after adjustment stops. Short-press the middle PWR button to cycle Navigation → Driving → Music. The PMIC retains its long-press power behavior; BOOT retains its startup bootloader function. Touch gestures and Spotify transport controls remain available.

Hold both outer buttons for one second to play the stationary speaker test at the selected volume. The ES8311 uses the manufacturer's `07_Audio_Test` C6 board pin map, shared I²C bus, 16 kHz stereo I²S (duplicated mono), and the board's speaker output. Connect the speaker to the board's designated speaker connector. No Bluetooth audio profile or microphone capture is used.

The Android companion silences its Navigation SDK audio. The display generates English maneuver and approach-distance prompts from an embedded phrase bank, independently of phone audio. Street names remain on screen; roundabouts ask you to follow the displayed exit. This is a limited local voice vocabulary, not a stream of Google's spoken instructions. Demo, disconnected, stale (5 seconds), unknown and inactive navigation states do not produce turn prompts. Changed steps cancel queued speech, and repeated snapshots do not repeat instructions. New phones send an optional `step_id` to distinguish consecutive identical maneuvers. Updating the phone to 0.3.0 is required to silence its existing guidance.

The phrase bank is generated with eSpeak NG 1.52 and ffmpeg by `python3 tools/generate_navigation_voice.py`; those tools are build-time conveniences, not firmware dependencies. Generated PCM is committed, so normal builds need neither tool. See `firmware/assets/README.md`. Host tests cover prompt gating, threshold progression, duplicate suppression, button debounce/repeat and volume-overlay rendering. Actual audibility, volume, buttons and driving timing require physical testing.

Validation for the 0.3.0 build: 129 Android unit tests, debug APK and lint passed; all three firmware host suites passed, including LVGL rendering; the sanitizer protocol oracle passed 19,440 comparisons. The 2,276,528-byte application was flashed to the connected ESP32-C6 app partition and independently verified, with a backup of the old application and partition/NVS region. Boot logs confirmed ES8311 output ready at 16 kHz, firmware 0.3.0, and encrypted reconnection with the existing bond. Live serial logs subsequently confirmed middle-button cycling through all three pages and volume-down presses changing 50% → 45% → 40%. Speaker audibility and volume-up still need the stationary user test; initialization logs alone do not prove audibility.
