#pragma once
#include "driver/i2c_master.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_touch.h"

// Verified against Waveshare's Arduino-v3.3.3/09_LVGL_V9_Test/user_config.h
// and the actual constructor defaults used by ESP-IDF-v5.5.3/09_LVGL_V9_Test.
namespace BoardPins {
constexpr int Scl=7, Sda=8, Clock=0, Data0=1, Data1=2, Data2=3, Data3=4;
constexpr int DisplayCs=15, TouchIrq=5, TouchReset=11;
}
class Board {
public:
    void init();
    void brightness(uint8_t percent);
    esp_lcd_panel_handle_t panel=nullptr;
    esp_lcd_panel_io_handle_t io=nullptr;
    esp_lcd_touch_handle_t touch=nullptr;
private:
    i2c_master_bus_handle_t bus=nullptr;
};
