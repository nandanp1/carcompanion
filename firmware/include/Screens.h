#pragma once
#include "DisplayState.h"
#include "lvgl.h"
#include <functional>
class DrivingScreen {
public:
    lv_obj_t* create();
    void update(const DisplayState&,uint64_t now);
private:
    lv_obj_t *speed_=nullptr,*limit_=nullptr,*clock_=nullptr,*status_=nullptr,*music_=nullptr;
};
class NavigationScreen {
public:
    lv_obj_t* create();
    void update(const DisplayState&,uint64_t now);
private:
    lv_obj_t *arrow_=nullptr,*distance_=nullptr,*road_=nullptr,*eta_=nullptr,*speed_=nullptr,*status_=nullptr;
    lv_obj_t* arrowLine_=nullptr;
    lv_point_precise_t arrowPoints_[6]={};
};
class MusicScreen {
public:
    lv_obj_t* create(std::function<void(uint8_t)> command);
    void update(const DisplayState&,uint64_t now,uint32_t cachedCrc,bool decodedValid);
    void artwork(const lv_image_dsc_t* image);
private:
    lv_obj_t *art_=nullptr,*title_=nullptr,*artist_=nullptr,*album_=nullptr,*progress_=nullptr,*play_=nullptr,*placeholder_=nullptr;
    std::function<void(uint8_t)> command_;
};
