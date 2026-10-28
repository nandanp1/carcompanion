#pragma once
#include "Board.h"
#include "NavigationAudio.h"
#include "ButtonDebouncer.h"
#include "ArtworkBuffer.h"
#include "SettingsManager.h"
#include "Screens.h"
#include "TouchManager.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include <functional>
#include <atomic>
class DisplayManager {
public:
    void init(Board&,SettingsManager&,NavigationAudio&,std::function<void(uint8_t)> playback);
    void update(const std::function<void(DisplayState&)>&);
    void connected(bool);
    void setBrightness(uint8_t);
    void clock(uint64_t unixSeconds,int offsetMinutes);
    void demo(bool enabled);
    ArtworkBuffer detachArtwork();
    void artwork(ArtworkBuffer&& pixels,uint16_t width,uint16_t height,uint32_t crc);
private:
    static void run(void*);
    static void flush(lv_display_t*,const lv_area_t*,uint8_t*);
    static void waitFlush(lv_display_t*);
    static bool flushed(esp_lcd_panel_io_handle_t,esp_lcd_panel_io_event_data_t*,void*);
    static void renderEvent(lv_event_t*);
    void wake();
    void refresh();
    void pollButtons(uint64_t now);
    NavigationAudio* audio_=nullptr;
    ButtonDebouncer downButton_,upButton_;
    VolumeOverlay volumeOverlay_;
    uint64_t volumeUntil_=0,volumeSaveAt_=0,lastPowerPoll_=0,bothSince_=0;
    bool bothTested_=false;

    Board* board_=nullptr; SettingsManager* settings_=nullptr;
    SemaphoreHandle_t gui_=nullptr,stateMutex_=nullptr,flushDone_=nullptr;
    TaskHandle_t task_=nullptr;
    std::atomic<bool> dirty_{true};
    uint64_t renderStartedUs_=0,renderTotalUs_=0,lastMetricsMs_=0;
    uint32_t renderCount_=0,renderMaxUs_=0;
    DisplayState state_;
    PageNavigation pages_;
    Page shown_=Page::Driving;
    DrivingScreen driving_; NavigationScreen navigation_; MusicScreen music_;
    lv_obj_t* screens_[3]={}; lv_display_t* lvDisplay_=nullptr;
    TouchManager touch_;
    lv_image_dsc_t image_={}; uint32_t cachedCrc_=0; bool decodedValid_=false;
    ArtworkBuffer artworkBuffer_;
    uint64_t clockUnix_=0,clockAt_=0; int offsetMinutes_=0;
    std::atomic<uint8_t> requestedBrightness_{0};
};
