#pragma once
#include "DisplayState.h"
#include "lvgl.h"
#include <functional>
class NowPlayingFooter {
public:
    void create(lv_obj_t* parent);
    void artwork(const lv_image_dsc_t* image);
    void update(const DisplayState&,uint32_t cachedCrc,bool decodedValid);
private:
    lv_obj_t *art_=nullptr,*title_=nullptr,*artist_=nullptr;
    std::string titleText_="No music",artistText_="Connect your phone";
    bool layoutWithArtwork_=false,playing_=false;
};
class DrivingScreen {
public:
    lv_obj_t* create();
    void update(const DisplayState&,uint64_t now,uint32_t cachedCrc,bool decodedValid);
    void artwork(const lv_image_dsc_t* image) { nowPlaying_.artwork(image); }
private:
    lv_obj_t *speed_=nullptr,*limit_=nullptr,*clock_=nullptr,*status_=nullptr;
    NowPlayingFooter nowPlaying_;
};
class NavigationScreen {
public:
    lv_obj_t* create();
    void update(const DisplayState&,uint64_t now,uint32_t cachedCrc,bool decodedValid);
    void artwork(const lv_image_dsc_t* image) { nowPlaying_.artwork(image); }
private:
    lv_obj_t *arrow_=nullptr,*distance_=nullptr,*road_=nullptr,*eta_=nullptr,*speed_=nullptr,*status_=nullptr;
    lv_obj_t* arrowLine_=nullptr;
    lv_point_precise_t arrowPoints_[6]={};
    std::string drawnManeuver_;
    std::string distanceText_="—",roadText_="No active route",etaText_;
    NowPlayingFooter nowPlaying_;
};
class MusicScreen {
public:
    lv_obj_t* create(std::function<void(uint8_t)> command);
    void update(const DisplayState&,uint64_t now,uint32_t cachedCrc,bool decodedValid);
    void artwork(const lv_image_dsc_t* image);
private:
    lv_obj_t *art_=nullptr,*title_=nullptr,*artist_=nullptr,*album_=nullptr,*progress_=nullptr,*play_=nullptr,*placeholder_=nullptr;
    std::function<void(uint8_t)> command_;
    uint32_t loggedExpectedCrc_=0,loggedDecodedCrc_=0;
    bool loggedPresence_=false,loggedDecoded_=false,visibilityLogged_=false;
    std::string titleText_="No music",artistText_="Connect your phone",albumText_;
};
// Keep shared descriptor attachment/detachment identical in production and
// renderer tests; callers must hold the LVGL mutex on the device.
void setScreenArtwork(DrivingScreen&,NavigationScreen&,MusicScreen&,const lv_image_dsc_t* image);

class VolumeOverlay {
public:
    void create(lv_obj_t* parent);
    void show(uint8_t volume,bool available,bool speaking);
    void hide();
private:
    lv_obj_t *panel_=nullptr,*title_=nullptr,*value_=nullptr;
    lv_obj_t* bars_[10]={};
};
