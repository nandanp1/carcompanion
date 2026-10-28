idf_component_register(SRCS "main.cpp" "Board.cpp" "DisplayManager.cpp" "TouchManager.cpp"
    "Screens.cpp" "SettingsManager.cpp" "BLEManager.cpp" "ArtworkReceiver.cpp"
    "Protocol.cpp" "NavigationAudio.cpp" "SpeedFont.c" "DashFont.c"
    INCLUDE_DIRS "../include"
    REQUIRES driver esp_lcd esp_timer nvs_flash bt json xpowers
    espressif__esp_lcd_sh8601 waveshare__esp_lcd_touch_cst9217
    lvgl__lvgl esp_rom espressif__esp_codec_dev)
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_17)

target_add_binary_data(${COMPONENT_LIB} "${CMAKE_CURRENT_LIST_DIR}/../assets/navigation.pcm" BINARY)
