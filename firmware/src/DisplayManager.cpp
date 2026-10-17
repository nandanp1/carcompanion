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
    auto* self=static_cast<DisplayManager*>(context);
    BaseType_t woken=pdFALSE;
    xSemaphoreGiveFromISR(self->flushDone_,&woken);
    // With a flush-wait callback LVGL clears its flushing flag after the wait.
    // Calling flush_ready here as well would leave a stale semaphore token,
    // allowing the next DMA buffer to be reused before its transfer finishes.
    return woken==pdTRUE;
}
void DisplayManager::waitFlush(lv_display_t* display) {
    auto* self=static_cast<DisplayManager*>(lv_display_get_user_data(display));
    ESP_ERROR_CHECK(xSemaphoreTake(self->flushDone_,pdMS_TO_TICKS(1000))==pdTRUE?ESP_OK:ESP_ERR_TIMEOUT);
}
void DisplayManager::flush(lv_display_t* display,const lv_area_t* area,uint8_t* data) {
    auto* self=static_cast<DisplayManager*>(lv_display_get_user_data(display));
    const size_t pixels=lv_area_get_width(area)*lv_area_get_height(area);
    lv_draw_sw_rgb565_swap(data,pixels);
    esp_err_t err=esp_lcd_panel_draw_bitmap(self->board_->panel,area->x1,area->y1,area->x2+1,area->y2+1,data);
    if(err!=ESP_OK) { ESP_LOGE("Display","flush: %s",esp_err_to_name(err)); xSemaphoreGive(self->flushDone_); }
}
void DisplayManager::init(Board& board,SettingsManager& settings,std::function<void(uint8_t)> playback) {
    board_=&board; settings_=&settings; pages_=PageNavigation(settings.page()); shown_=pages_.visible();
    gui_=xSemaphoreCreateMutex(); stateMutex_=xSemaphoreCreateMutex(); flushDone_=xSemaphoreCreateBinary();
    assert(gui_ && stateMutex_ && flushDone_);
    lv_init(); lv_tick_set_cb(tick); lvDisplay_=lv_display_create(480,480);
    lv_display_set_color_format(lvDisplay_,LV_COLOR_FORMAT_RGB565);
    constexpr size_t stripeBytes=480*10*2;
    auto* buffer=static_cast<uint8_t*>(heap_caps_malloc(stripeBytes*2,MALLOC_CAP_DMA|MALLOC_CAP_INTERNAL)); assert(buffer);
    // Same 19.2 KiB allocation as one 20-line stripe, but render the next
    // 10-line stripe while DMA transfers the previous one.
    lv_display_set_buffers(lvDisplay_,buffer,buffer+stripeBytes,stripeBytes,LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_user_data(lvDisplay_,this); lv_display_set_flush_cb(lvDisplay_,flush);
    lv_display_set_flush_wait_cb(lvDisplay_,waitFlush);
    lv_timer_set_period(lv_display_get_refr_timer(lvDisplay_),16);
    lv_display_add_event_cb(lvDisplay_,roundArea,LV_EVENT_INVALIDATE_AREA,nullptr);
    lv_display_add_event_cb(lvDisplay_,renderEvent,LV_EVENT_RENDER_START,this);
    lv_display_add_event_cb(lvDisplay_,renderEvent,LV_EVENT_RENDER_READY,this);
    esp_lcd_panel_io_callbacks_t cb={}; cb.on_color_trans_done=flushed;
    ESP_ERROR_CHECK(esp_lcd_panel_io_register_event_callbacks(board.io,&cb,this));
    screens_[0]=navigation_.create(); screens_[1]=driving_.create(); screens_[2]=music_.create(playback);
    lv_screen_load(screens_[static_cast<int>(shown_)]);
    touch_.init(board,lvDisplay_,[this](int dx,int dy,uint32_t duration) {
        if(pages_.swipe(dx,dy,duration)) { settings_->setPage(pages_.selected()); wake(); }
    });
    board.brightness(settings.brightness());
    const BaseType_t ok=xTaskCreate(run,"display",6144,this,5,&task_); assert(ok==pdPASS);
    ESP_LOGI("Display","40 MHz QSPI; two 10-line DMA buffers; refresh/input target 16 ms");
}
void DisplayManager::wake() {
    dirty_=true;
    if(task_) xTaskNotifyGive(task_);
}
void DisplayManager::renderEvent(lv_event_t* event) {
    auto* self=static_cast<DisplayManager*>(lv_event_get_user_data(event));
    if(lv_event_get_code(event)==LV_EVENT_RENDER_START) self->renderStartedUs_=esp_timer_get_time();
    else {
        const uint32_t elapsed=esp_timer_get_time()-self->renderStartedUs_;
        self->renderTotalUs_+=elapsed; self->renderMaxUs_=std::max(self->renderMaxUs_,elapsed); ++self->renderCount_;
    }
}
void DisplayManager::run(void* ptr) {
    auto* self=static_cast<DisplayManager*>(ptr);
    uint64_t last=0;
    while(true) {
        xSemaphoreTake(self->gui_,portMAX_DELAY);
        if(self->dirty_.exchange(false) || millis()-last>=100) { self->refresh(); last=millis(); }
        const uint32_t delay=lv_timer_handler();
        xSemaphoreGive(self->gui_);
        const uint64_t now=millis();
        if(now-self->lastMetricsMs_>=5000) {
            if(self->renderCount_) ESP_LOGI("Display","rendered=%lu in %llu ms; average=%llu us max=%lu us; heap=%u largest=%u",
                static_cast<unsigned long>(self->renderCount_),static_cast<unsigned long long>(now-self->lastMetricsMs_),
                static_cast<unsigned long long>(self->renderTotalUs_/self->renderCount_),static_cast<unsigned long>(self->renderMaxUs_),
                heap_caps_get_free_size(MALLOC_CAP_8BIT),heap_caps_get_largest_free_block(MALLOC_CAP_8BIT));
            self->lastMetricsMs_=now; self->renderCount_=0; self->renderTotalUs_=0; self->renderMaxUs_=0;
        }
        ulTaskNotifyTake(pdTRUE,pdMS_TO_TICKS(std::clamp<uint32_t>(delay,1,16)));
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
    pages_.navigation(snapshot.navigationActive && (snapshot.connected || snapshot.demo));
    if(pages_.selected()!=selectedBefore) settings_->setPage(pages_.selected());
    if(shown_!=pages_.visible()) {
        const auto next=pages_.visible();
        lv_screen_load_anim(screens_[static_cast<int>(next)],static_cast<int>(next)>static_cast<int>(shown_)?LV_SCR_LOAD_ANIM_MOVE_LEFT:LV_SCR_LOAD_ANIM_MOVE_RIGHT,180,0,false);
        shown_=next;
    }
    driving_.update(snapshot,now,cachedCrc_,decodedValid_);
    navigation_.update(snapshot,now,cachedCrc_,decodedValid_);
    music_.update(snapshot,now,cachedCrc_,decodedValid_);
}
void DisplayManager::update(const std::function<void(DisplayState&)>& fn) {
    xSemaphoreTake(stateMutex_,portMAX_DELAY); fn(state_); xSemaphoreGive(stateMutex_);
    wake();
}
void DisplayManager::connected(bool value) {
    update([value](DisplayState& s) { s.connected=value; if(!value) { s.gpsValid=false; s.navigationActive=false; s.playing=false; } });
}
void DisplayManager::setBrightness(uint8_t value) {
    requestedBrightness_=value; wake();
}
void DisplayManager::clock(uint64_t unixSeconds,int offsetMinutes) {
    xSemaphoreTake(stateMutex_,portMAX_DELAY); clockUnix_=unixSeconds; clockAt_=millis(); offsetMinutes_=offsetMinutes; xSemaphoreGive(stateMutex_);
    wake();
}
void DisplayManager::demo(bool enabled) {
    update([enabled](DisplayState& state) { const bool connected=state.connected; state=DisplayState{}; state.connected=connected; state.demo=enabled; });
}
ArtworkBuffer DisplayManager::detachArtwork() {
    ArtworkBuffer detached;
    xSemaphoreTake(gui_,portMAX_DELAY);
    // All three widgets reference the same descriptor. Remove every reference
    // before the decoder may overwrite or free its one owned pixel buffer.
    setScreenArtwork(driving_,navigation_,music_,nullptr);
    cachedCrc_=0; decodedValid_=false;
    if(image_.data) lv_image_cache_drop(&image_);
    image_={}; detached=std::move(artworkBuffer_);
    xSemaphoreGive(gui_);
    wake();
    return detached;
}
void DisplayManager::artwork(ArtworkBuffer&& pixels,uint16_t width,uint16_t height,uint32_t crc) {
    xSemaphoreTake(gui_,portMAX_DELAY);
    assert(!artworkBuffer_.data() && pixels.data() && pixels.capacity()>=static_cast<size_t>(width)*height*2);
    artworkBuffer_=std::move(pixels);
    image_={}; image_.header.magic=LV_IMAGE_HEADER_MAGIC; image_.header.cf=LV_COLOR_FORMAT_RGB565;
    image_.header.w=width; image_.header.h=height; image_.header.stride=width*2;
    image_.data_size=width*height*2; image_.data=artworkBuffer_.data(); cachedCrc_=crc; decodedValid_=true;
    setScreenArtwork(driving_,navigation_,music_,&image_);
    xSemaphoreGive(gui_);
    wake();
}
