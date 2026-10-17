# Production screen render tests

After `idf.py reconfigure` or `idf.py build` has downloaded the pinned LVGL component and generated the firmware configuration, run from the repository root:

```sh
cmake -S firmware/tests -B .cache/firmware-layout-tests \
  -DCOMPANION_TEST_LVGL=ON \
  -DCMAKE_C_COMPILER=/usr/bin/clang -DCMAKE_CXX_COMPILER=/usr/bin/clang++
cmake --build .cache/firmware-layout-tests -j 8
ctest --test-dir .cache/firmware-layout-tests --output-on-failure
```

The optional target uses the actual production `Screens.cpp`, fonts, LVGL version and generated LVGL configuration. Only the ESP log sink and display transport are replaced. It checks long music/driving metadata geometry, ellipsis, embedded line breaks, empty fields, and absence of redraws for unchanged truncated text. Pixel assertions cover decoded-image attachment, CRC mismatch/zero, reduced-size replacement, and deferred two-buffer transfers. PPM renders are written in the test working directory for inspection.

ASan and UBSan are enabled by default. Clang's function-pointer type check is excluded for the third-party LVGL library because its generic callback conventions trigger it; other checks remain enabled. This host test does not execute ROM JPEG decoding, the physical panel, FreeRTOS or BLE.
