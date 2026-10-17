#pragma once
#include "DisplayState.h"
#include "nvs.h"
class SettingsManager {
public:
    void init();
    uint8_t brightness() const { return brightness_; }
    Page page() const { return page_; }
    void setBrightness(uint8_t value);
    void setPage(Page value);
private:
    nvs_handle_t handle_=0;
    uint8_t brightness_=50;
    Page page_=Page::Driving;
};
