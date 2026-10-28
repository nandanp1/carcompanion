#pragma once
#include "DisplayState.h"
#include "nvs.h"
class SettingsManager {
public:
    void init();
    uint8_t brightness() const { return brightness_; }
    uint8_t volume() const { return volume_; }
    void setVolume(uint8_t value);
    Page page() const { return page_; }
    void setBrightness(uint8_t value);
    void setPage(Page value);
private:
    nvs_handle_t handle_=0;
    uint8_t brightness_=50,volume_=50;
    Page page_=Page::Driving;
};
