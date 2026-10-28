#include "Screens.h"
#include <cstdio>
#include <cmath>
#include "esp_log.h"
LV_FONT_DECLARE(SpeedFont);
LV_FONT_DECLARE(DashFont);
namespace {
constexpr uint32_t White=0xF3F4F5,Grey=0x8C929A,Blue=0x5B9DDC,Green=0x7ABE91;
lv_obj_t* page() {
    lv_obj_t* p=lv_obj_create(nullptr); lv_obj_set_style_bg_color(p,lv_color_black(),0);
    lv_obj_set_style_text_color(p,lv_color_hex(White),0); lv_obj_set_style_pad_all(p,0,0);
    lv_obj_remove_flag(p,LV_OBJ_FLAG_SCROLLABLE); return p;
}
lv_obj_t* label(lv_obj_t* parent,const char* text,int x,int y,int width,const lv_font_t* font,uint32_t color=White) {
    // Built-in LVGL Montserrat omits U+2014. Add the generated dash as a
    // fallback so unavailable data renders as an em dash rather than a box.
    static lv_font_t f16=lv_font_montserrat_16,f20=lv_font_montserrat_20,f24=lv_font_montserrat_24,f48=lv_font_montserrat_48;
    f16.fallback=f20.fallback=f24.fallback=f48.fallback=&DashFont;
    if(font==&lv_font_montserrat_16) font=&f16;
    else if(font==&lv_font_montserrat_20) font=&f20;
    else if(font==&lv_font_montserrat_24) font=&f24;
    else if(font==&lv_font_montserrat_48) font=&f48;
    auto* o=lv_label_create(parent); lv_label_set_text(o,text); lv_obj_set_pos(o,x,y); lv_obj_set_width(o,width);
    lv_obj_set_style_text_font(o,font,0); lv_obj_set_style_text_color(o,lv_color_hex(color),0);
    lv_obj_set_style_text_align(o,LV_TEXT_ALIGN_CENTER,0); lv_label_set_long_mode(o,LV_LABEL_LONG_DOT); return o;
}
void set(lv_obj_t* label,const std::string& text) { if(text!=lv_label_get_text(label)) lv_label_set_text(label,text.c_str()); }
lv_obj_t* singleLineLabel(lv_obj_t* parent,const char* text,int x,int y,int width,const lv_font_t* font,uint32_t color=White) {
    auto* o=label(parent,text,x,y,width,font,color);
    // LONG_DOT needs a constrained height: content-sized labels still wrap.
    lv_obj_set_height(o,lv_font_get_line_height(font));
    lv_obj_set_style_text_line_space(o,0,0);
    return o;
}
void setMetadata(lv_obj_t* label,const std::string& text,std::string& original) {
    // LVGL's dotted mode modifies the displayed string. Compare against the
    // original metadata so truncation does not invalidate the label every tick.
    if(original==text) return;
    original=text;
    std::string line; line.reserve(text.size());
    for(char c:text) {
        if(c=='\r' || c=='\n' || c=='\t' || c==' ') {
            if(!line.empty() && line.back()!=' ') line.push_back(' ');
        } else line.push_back(c);
    }
    if(!line.empty() && line.back()==' ') line.pop_back();
    lv_label_set_text(label,line.c_str());
}
std::string speed(const DisplayState& s,uint64_t now) {
    if(!s.speedAvailable(now)) return "—";
    char value[8]; snprintf(value,sizeof(value),"%.0f",s.speedMph); return value;
}
std::string limit(const DisplayState& s,uint64_t now) {
    if(!s.limitAvailable(now)) return "LIMIT  —";
    char value[20]; snprintf(value,sizeof(value),"LIMIT  %.0f",s.speedLimitMph); return value;
}
}
void NowPlayingFooter::create(lv_obj_t* parent) {
    art_=lv_image_create(parent); lv_obj_set_pos(art_,104,378); lv_obj_set_size(art_,52,52);
    // Image-widget scaling renders into the existing clipped draw stripes;
    // this is not an object transform or a second decoded-artwork allocation.
    lv_image_set_inner_align(art_,LV_IMAGE_ALIGN_CONTAIN);
    lv_obj_add_flag(art_,LV_OBJ_FLAG_HIDDEN);
    title_=singleLineLabel(parent,"No music",104,381,280,&lv_font_montserrat_16);
    artist_=singleLineLabel(parent,"Connect your phone",104,406,280,&lv_font_montserrat_16,Grey);
}
void NowPlayingFooter::artwork(const lv_image_dsc_t* image) {
    lv_image_set_src(art_,image);
    if(!image) lv_obj_add_flag(art_,LV_OBJ_FLAG_HIDDEN);
}
void NowPlayingFooter::update(const DisplayState& s,uint32_t cachedCrc,bool decodedValid) {
    const bool show=decodedValid && s.artworkPresent && s.artworkId==cachedCrc && lv_image_get_src(art_);
    if(show) lv_obj_remove_flag(art_,LV_OBJ_FLAG_HIDDEN);
    else lv_obj_add_flag(art_,LV_OBJ_FLAG_HIDDEN);
    if(show!=layoutWithArtwork_) {
        layoutWithArtwork_=show;
        for(auto* text:{title_,artist_}) {
            lv_obj_set_x(text,show?172:104); lv_obj_set_width(text,show?212:280);
            lv_obj_set_style_text_align(text,show?LV_TEXT_ALIGN_LEFT:LV_TEXT_ALIGN_CENTER,0);
        }
    }
    if(playing_!=s.playing) { playing_=s.playing; lv_obj_set_style_text_color(title_,lv_color_hex(playing_?Green:White),0); }
    setMetadata(title_,s.title,titleText_); setMetadata(artist_,s.artist,artistText_);
}
lv_obj_t* DrivingScreen::create() {
    auto* p=page(); label(p,"986  /  CAR COMPANION",90,39,300,&lv_font_montserrat_16,Grey);
    clock_=label(p,"--:--",155,76,170,&lv_font_montserrat_24);
    speed_=label(p,"—",65,122,350,&SpeedFont);
    label(p,"MPH",190,244,100,&lv_font_montserrat_20,Grey);
    limit_=label(p,"LIMIT  —",140,285,200,&lv_font_montserrat_20);
    status_=label(p,"PHONE DISCONNECTED",90,331,300,&lv_font_montserrat_16,Grey);
    nowPlaying_.create(p);
    return p;
}
void DrivingScreen::update(const DisplayState& s,uint64_t now,uint32_t cachedCrc,bool decodedValid) {
    set(speed_,speed(s,now)); set(limit_,limit(s,now)); set(clock_,s.clock);
    set(status_,s.demo?"DEMO • SIMULATED DATA":!s.connected?"PHONE DISCONNECTED":!s.speedAvailable(now)?"GPS UNAVAILABLE":"BLUETOOTH CONNECTED");
    nowPlaying_.update(s,cachedCrc,decodedValid);
}
lv_obj_t* NavigationScreen::create() {
    auto* p=page(); status_=singleLineLabel(p,"NAVIGATION",90,39,300,&lv_font_montserrat_16);
    arrow_=label(p,"—",150,91,180,&lv_font_montserrat_48,Blue);
    arrowLine_=lv_line_create(p); lv_obj_set_pos(arrowLine_,180,78);
    lv_obj_set_style_line_color(arrowLine_,lv_color_hex(Blue),0);
    lv_obj_set_style_line_width(arrowLine_,10,0); lv_obj_set_style_line_rounded(arrowLine_,true,0);
    lv_obj_add_flag(arrowLine_,LV_OBJ_FLAG_HIDDEN);
    distance_=singleLineLabel(p,"—",80,192,320,&lv_font_montserrat_48);
    road_=label(p,"No active route",60,250,360,&lv_font_montserrat_24);
    lv_obj_set_height(road_,2*lv_font_get_line_height(&lv_font_montserrat_24));
    lv_obj_set_style_text_line_space(road_,0,0);
    eta_=singleLineLabel(p,"",65,310,350,&lv_font_montserrat_20,Grey);
    speed_=singleLineLabel(p,"— MPH     LIMIT  —",65,344,350,&lv_font_montserrat_20);
    nowPlaying_.create(p);
    return p;
}
void NavigationScreen::update(const DisplayState& s,uint64_t now,uint32_t cachedCrc,bool decodedValid) {
    const std::string maneuver=s.navigationActive?s.maneuver:"inactive";
    // Setting line points invalidates the arrow even when unchanged. Only
    // redraw its geometry when the maneuver changes, not on every speed tick.
    if(maneuver!=drawnManeuver_) {
    drawnManeuver_=maneuver;
    std::string symbol="—";
    bool line=false;
    if(s.navigationActive) {
        if(s.maneuver=="left" || s.maneuver=="right") {
            const lv_point_precise_t p[6]={{90,100},{90,40},{15,40},{45,10},{15,40},{45,70}};
            for(unsigned i=0;i<6;i++) { arrowPoints_[i]=p[i]; if(s.maneuver=="right") arrowPoints_[i].x=120-arrowPoints_[i].x; }
            line=true;
        }
        else if(s.maneuver=="straight" || s.maneuver=="slight_left" || s.maneuver=="slight_right") {
            const lv_point_precise_t p[6]={{60,110},{60,55},{60,10},{25,45},{60,10},{95,45}};
            for(unsigned i=0;i<6;i++) arrowPoints_[i]=p[i];
            if(s.maneuver=="slight_left") {
                const lv_point_precise_t q[6]={{90,110},{90,75},{25,10},{25,50},{25,10},{65,10}};
                for(unsigned i=0;i<6;i++) arrowPoints_[i]=q[i];
            } else if(s.maneuver=="slight_right") {
                const lv_point_precise_t q[6]={{30,110},{30,75},{95,10},{55,10},{95,10},{95,50}};
                for(unsigned i=0;i<6;i++) arrowPoints_[i]=q[i];
            }
            line=true;
        }
        else if(s.maneuver=="arrive") symbol=LV_SYMBOL_OK;
        else if(s.maneuver=="uturn") symbol=LV_SYMBOL_LOOP;
        else if(s.maneuver=="roundabout") symbol=LV_SYMBOL_REFRESH;
    }
    if(line) { lv_line_set_points(arrowLine_,arrowPoints_,6); lv_obj_remove_flag(arrowLine_,LV_OBJ_FLAG_HIDDEN); lv_obj_add_flag(arrow_,LV_OBJ_FLAG_HIDDEN); }
    else { lv_obj_add_flag(arrowLine_,LV_OBJ_FLAG_HIDDEN); lv_obj_remove_flag(arrow_,LV_OBJ_FLAG_HIDDEN); }
    set(arrow_,symbol);
    }
    setMetadata(distance_,s.navigationActive&&!s.distance.empty()?s.distance:"—",distanceText_);
    setMetadata(road_,s.navigationActive?(s.road.empty()?"Instruction unavailable":s.road):"No active route",roadText_);
    setMetadata(eta_,s.navigationActive?s.eta:"",etaText_); set(speed_,speed(s,now)+" MPH     "+limit(s,now));
    set(status_,s.demo?"DEMO NAVIGATION":s.navigationActive?"Google Maps":"NAVIGATION");
    nowPlaying_.update(s,cachedCrc,decodedValid);
}
lv_obj_t* MusicScreen::create(std::function<void(uint8_t)> command) {
    command_=command; auto* p=page(); label(p,"SPOTIFY",130,25,220,&lv_font_montserrat_16,Green);
    placeholder_=label(p,LV_SYMBOL_AUDIO,120,116,240,&lv_font_montserrat_48,Grey);
    art_=lv_image_create(p); lv_obj_set_pos(art_,120,56); lv_obj_add_flag(art_,LV_OBJ_FLAG_HIDDEN);
    title_=singleLineLabel(p,"No music",55,310,370,&lv_font_montserrat_24);
    artist_=singleLineLabel(p,"Connect your phone",55,345,370,&lv_font_montserrat_20,Grey);
    album_=singleLineLabel(p,"",60,374,360,&lv_font_montserrat_16,Grey);
    progress_=lv_bar_create(p); lv_obj_set_pos(progress_,85,405); lv_obj_set_size(progress_,310,3);
    lv_obj_set_style_bg_color(progress_,lv_color_hex(0x262A2D),0);
    lv_obj_set_style_bg_color(progress_,lv_color_hex(Green),LV_PART_INDICATOR); lv_bar_set_range(progress_,0,1000);
    for(int index=0;index<3;index++) {
        auto* b=lv_button_create(p); lv_obj_set_pos(b,145+index*66,416); lv_obj_set_size(b,58,40);
        lv_obj_set_style_bg_color(b,lv_color_hex(0x121518),0); lv_obj_set_style_shadow_width(b,0,0);
        lv_obj_set_user_data(b,this);
        auto* t=lv_label_create(b); lv_label_set_text(t,index==0?LV_SYMBOL_PREV:index==1?LV_SYMBOL_PLAY:LV_SYMBOL_NEXT);
        lv_obj_center(t); if(index==1) play_=t;
        lv_obj_add_event_cb(b,[](lv_event_t* event) {
            auto* obj=lv_event_get_target_obj(event);
            auto* self=static_cast<MusicScreen*>(lv_obj_get_user_data(obj));
            const uintptr_t cmd=reinterpret_cast<uintptr_t>(lv_event_get_user_data(event));
            if(self->command_) self->command_(static_cast<uint8_t>(cmd));
        },LV_EVENT_CLICKED,reinterpret_cast<void*>(index==0?3:index==1?1:2));
    }
    return p;
}
void MusicScreen::artwork(const lv_image_dsc_t* image) {
    if(image) {
        lv_image_set_src(art_,image); lv_obj_set_pos(art_,(480-image->header.w)/2,56+(240-image->header.h)/2);
        ESP_LOGI("Music","art attached %ux%u bytes=%lu source_accepted=%u",image->header.w,image->header.h,
            static_cast<unsigned long>(image->data_size),lv_image_get_src(art_)==image);
    }
    else { lv_image_set_src(art_,nullptr); lv_obj_add_flag(art_,LV_OBJ_FLAG_HIDDEN); }
}
void MusicScreen::update(const DisplayState& s,uint64_t now,uint32_t cachedCrc,bool decodedValid) {
    setMetadata(title_,s.title,titleText_); setMetadata(artist_,s.artist,artistText_);
    setMetadata(album_,s.demo?"DEMO • SIMULATED DATA":s.album,albumText_);
    set(play_,s.playing?LV_SYMBOL_PAUSE:LV_SYMBOL_PLAY);
    const bool show=decodedValid && s.artworkPresent && s.artworkId==cachedCrc;
    if(!visibilityLogged_ || loggedPresence_!=s.artworkPresent || loggedDecoded_!=decodedValid ||
       loggedExpectedCrc_!=s.artworkId || loggedDecodedCrc_!=cachedCrc) {
        ESP_LOGI("Music","art visible=%u metadata_present=%u expected=%08lx decoded_valid=%u cached=%08lx",show,s.artworkPresent,
            static_cast<unsigned long>(s.artworkId),decodedValid,static_cast<unsigned long>(cachedCrc));
        loggedPresence_=s.artworkPresent; loggedDecoded_=decodedValid; loggedExpectedCrc_=s.artworkId;
        loggedDecodedCrc_=cachedCrc; visibilityLogged_=true;
    }
    if(show) { lv_obj_remove_flag(art_,LV_OBJ_FLAG_HIDDEN); lv_obj_add_flag(placeholder_,LV_OBJ_FLAG_HIDDEN); }
    else { lv_obj_add_flag(art_,LV_OBJ_FLAG_HIDDEN); lv_obj_remove_flag(placeholder_,LV_OBJ_FLAG_HIDDEN); }
    uint64_t position=s.positionMs;
    if(s.playing && s.connected && now>=s.musicReceivedMs) position+=now-s.musicReceivedMs;
    lv_bar_set_value(progress_,s.durationMs?static_cast<int>(std::min<uint64_t>(position,s.durationMs)*1000/s.durationMs):0,LV_ANIM_OFF);
}
void setScreenArtwork(DrivingScreen& driving,NavigationScreen& navigation,MusicScreen& music,const lv_image_dsc_t* image) {
    driving.artwork(image); navigation.artwork(image); music.artwork(image);
}
