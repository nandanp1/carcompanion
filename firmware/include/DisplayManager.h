#pragma once
#include "Board.h"
#include "SettingsManager.h"
#include "Screens.h"
#include "TouchManager.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include <functional>
#include <atomic>
class DisplayManager {
public:
    void init(Board&,SettingsManager&,std::function<void(uint8_t)> playback);
    void update(const std::function<void(DisplayState&)>&);
    void connected(bool);
    void setBrightness(uint8_t);
    void clock(uint64_t unixSeconds,int offsetMinutes);
    void demo(bool enabled);
    void detachArtwork();
    void artwork(uint8_t* pixels,uint16_t width,uint16_t height,uint32_t crc);
private:
    static void run(void*);
    static void flush(lv_display_t*,const lv_area_t*,uint8_t*);
    static bool flushed(esp_lcd_panel_io_handle_t,esp_lcd_panel_io_event_data_t*,void*);
    void refresh();
    Board* board_=nullptr; SettingsManager* settings_=nullptr;
    SemaphoreHandle_t gui_=nullptr,stateMutex_=nullptr;
    DisplayState state_;
    PageNavigation pages_;
    Page shown_=Page::Driving;
    DrivingScreen driving_; NavigationScreen navigation_; MusicScreen music_;
    lv_obj_t* screens_[3]={}; lv_display_t* lvDisplay_=nullptr;
    TouchManager touch_;
    lv_image_dsc_t image_={}; uint32_t cachedCrc_=0; bool decodedValid_=false;
    uint64_t clockUnix_=0,clockAt_=0; int offsetMinutes_=0;
    std::atomic<uint8_t> requestedBrightness_{0};
};
