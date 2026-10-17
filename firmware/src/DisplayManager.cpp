#include "DisplayManager.h"
#include "esp_timer.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "src/misc/cache/instance/lv_image_cache.h"
#include <ctime>
#include <cmath>
namespace {
uint64_t millis() { return esp_timer_get_time()/1000; }
uint32_t tick() { return static_cast<uint32_t>(millis()); }
void roundArea(lv_event_t* event) {
    auto* a=static_cast<lv_area_t*>(lv_event_get_param(event));
    a->x1&=~1; a->y1&=~1; a->x2|=1; a->y2|=1;
}
}
bool DisplayManager::flushed(esp_lcd_panel_io_handle_t,esp_lcd_panel_io_event_data_t*,void* context) {
    lv_display_flush_ready(static_cast<lv_display_t*>(context)); return false;
}
void DisplayManager::flush(lv_display_t* display,const lv_area_t* area,uint8_t* data) {
    auto* self=static_cast<DisplayManager*>(lv_display_get_user_data(display));
    const size_t pixels=lv_area_get_width(area)*lv_area_get_height(area);
    lv_draw_sw_rgb565_swap(data,pixels);
    esp_err_t err=esp_lcd_panel_draw_bitmap(self->board_->panel,area->x1,area->y1,area->x2+1,area->y2+1,data);
    if(err!=ESP_OK) { ESP_LOGE("Display","flush: %s",esp_err_to_name(err)); lv_display_flush_ready(display); }
}
void DisplayManager::init(Board& board,SettingsManager& settings,std::function<void(uint8_t)> playback) {
    board_=&board; settings_=&settings; pages_=PageNavigation(settings.page()); shown_=pages_.visible();
    gui_=xSemaphoreCreateMutex(); stateMutex_=xSemaphoreCreateMutex(); assert(gui_ && stateMutex_);
    lv_init(); lv_tick_set_cb(tick); lvDisplay_=lv_display_create(480,480);
    lv_display_set_color_format(lvDisplay_,LV_COLOR_FORMAT_RGB565);
    void* buffer=heap_caps_malloc(480*20*2,MALLOC_CAP_DMA|MALLOC_CAP_INTERNAL); assert(buffer);
    lv_display_set_buffers(lvDisplay_,buffer,nullptr,480*20*2,LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_user_data(lvDisplay_,this); lv_display_set_flush_cb(lvDisplay_,flush);
    lv_display_add_event_cb(lvDisplay_,roundArea,LV_EVENT_INVALIDATE_AREA,nullptr);
    esp_lcd_panel_io_callbacks_t cb={}; cb.on_color_trans_done=flushed;
    ESP_ERROR_CHECK(esp_lcd_panel_io_register_event_callbacks(board.io,&cb,lvDisplay_));
    screens_[0]=navigation_.create(); screens_[1]=driving_.create(); screens_[2]=music_.create(playback);
    lv_screen_load(screens_[static_cast<int>(shown_)]);
    touch_.init(board,lvDisplay_,[this](int dx,int dy,uint32_t duration) {
        if(pages_.swipe(dx,dy,duration)) settings_->setPage(pages_.selected());
    });
    board.brightness(settings.brightness());
    const BaseType_t ok=xTaskCreate(run,"display",6144,this,5,nullptr); assert(ok==pdPASS);
}
void DisplayManager::run(void* ptr) {
    auto* self=static_cast<DisplayManager*>(ptr);
    uint64_t last=0;
    while(true) {
        xSemaphoreTake(self->gui_,portMAX_DELAY);
        if(millis()-last>=100) { self->refresh(); last=millis(); }
        const uint32_t delay=lv_timer_handler();
        xSemaphoreGive(self->gui_);
        vTaskDelay(pdMS_TO_TICKS(std::clamp<uint32_t>(delay,5,20)));
    }
}
void DisplayManager::refresh() {
    const uint64_t now=millis();
    const uint8_t brightness=requestedBrightness_.exchange(0);
    if(brightness) { board_->brightness(brightness); settings_->setBrightness(brightness); }
    xSemaphoreTake(stateMutex_,portMAX_DELAY);
    if(clockUnix_) {
        const time_t time=clockUnix_+(now-clockAt_)/1000+offsetMinutes_*60;
        tm local={}; gmtime_r(&time,&local); char text[8]; strftime(text,sizeof(text),"%H:%M",&local); state_.clock=text;
    }
    if(state_.demo && !state_.connected) {
        state_.gpsValid=true; state_.gpsAgeMs=0; state_.drivingReceivedMs=now;
        state_.speedMph=40+12*std::sin(now/8000.0); state_.speedLimitMph=-1;
        state_.title="Night Drive"; state_.artist="Simulated media"; state_.album="Car Companion demo";
        state_.navigationActive=(now/20000)%3==1;
        state_.maneuver="right"; state_.distance="500 ft"; state_.road="Demo Avenue"; state_.eta="";
    }
    const DisplayState snapshot=state_; xSemaphoreGive(stateMutex_);
    const Page selectedBefore=pages_.selected();
    pages_.navigation(snapshot.navigationActive);
    if(pages_.selected()!=selectedBefore) settings_->setPage(pages_.selected());
    if(shown_!=pages_.visible()) {
        const auto next=pages_.visible();
        lv_screen_load_anim(screens_[static_cast<int>(next)],static_cast<int>(next)>static_cast<int>(shown_)?LV_SCR_LOAD_ANIM_MOVE_LEFT:LV_SCR_LOAD_ANIM_MOVE_RIGHT,180,0,false);
        shown_=next;
    }
    driving_.update(snapshot,now); navigation_.update(snapshot,now); music_.update(snapshot,now,cachedCrc_,decodedValid_);
}
void DisplayManager::update(const std::function<void(DisplayState&)>& fn) {
    xSemaphoreTake(stateMutex_,portMAX_DELAY); fn(state_); xSemaphoreGive(stateMutex_);
}
void DisplayManager::connected(bool value) {
    update([value](DisplayState& s) { s.connected=value; if(!value) { s.gpsValid=false; s.navigationActive=false; s.playing=false; } });
}
void DisplayManager::setBrightness(uint8_t value) {
    requestedBrightness_=value;
}
void DisplayManager::clock(uint64_t unixSeconds,int offsetMinutes) {
    xSemaphoreTake(stateMutex_,portMAX_DELAY); clockUnix_=unixSeconds; clockAt_=millis(); offsetMinutes_=offsetMinutes; xSemaphoreGive(stateMutex_);
}
void DisplayManager::demo(bool enabled) {
    update([enabled](DisplayState& state) { const bool connected=state.connected; state=DisplayState{}; state.connected=connected; state.demo=enabled; });
}
void DisplayManager::detachArtwork() {
    xSemaphoreTake(gui_,portMAX_DELAY); music_.artwork(nullptr); cachedCrc_=0; decodedValid_=false;
    if(image_.data) { lv_image_cache_drop(&image_); free(const_cast<uint8_t*>(image_.data)); image_={}; }
    xSemaphoreGive(gui_);
}
void DisplayManager::artwork(uint8_t* pixels,uint16_t width,uint16_t height,uint32_t crc) {
    xSemaphoreTake(gui_,portMAX_DELAY);
    image_={}; image_.header.magic=LV_IMAGE_HEADER_MAGIC; image_.header.cf=LV_COLOR_FORMAT_RGB565;
    image_.header.w=width; image_.header.h=height; image_.header.stride=width*2;
    image_.data_size=width*height*2; image_.data=pixels; cachedCrc_=crc; decodedValid_=true; music_.artwork(&image_);
    xSemaphoreGive(gui_);
}
