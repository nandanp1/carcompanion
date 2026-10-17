#include "TouchManager.h"
void TouchManager::init(Board& board,lv_display_t* display,std::function<void(int,int,uint32_t)> swipe) {
    board_=&board; swipe_=swipe;
    lv_indev_t* input=lv_indev_create(); lv_indev_set_type(input,LV_INDEV_TYPE_POINTER);
    lv_indev_set_display(input,display); lv_indev_set_user_data(input,this); lv_indev_set_read_cb(input,read);
    lv_timer_set_period(lv_indev_get_read_timer(input),16);
}
void TouchManager::read(lv_indev_t* input,lv_indev_data_t* data) {
    auto* self=static_cast<TouchManager*>(lv_indev_get_user_data(input));
    esp_lcd_touch_point_data_t point={}; uint8_t count=0;
    const bool readOk=esp_lcd_touch_read_data(self->board_->touch)==ESP_OK;
    const bool down=readOk && esp_lcd_touch_get_data(self->board_->touch,&point,&count,1)==ESP_OK && count;
    const int x=std::min<int>(point.x,479),y=std::min<int>(point.y,479);
    const uint32_t now=lv_tick_get();
    if(down) {
        if(!self->pressed_) { self->x_=x; self->y_=y; self->downAt_=now; }
        self->lastX_=x; self->lastY_=y;
    } else if(self->pressed_) {
        const int dx=self->lastX_-self->x_,dy=self->lastY_-self->y_;
        if(std::abs(dx)>=70 && std::abs(dx)>=2*std::abs(dy) && now-self->downAt_<=900) {
            // Prevent a swipe ending over a playback control from also clicking it.
            lv_indev_reset(input,nullptr);
            self->swipe_(dx,dy,now-self->downAt_);
        }
    }
    self->pressed_=down; data->point.x=self->lastX_; data->point.y=self->lastY_;
    data->state=down?LV_INDEV_STATE_PRESSED:LV_INDEV_STATE_RELEASED;
}
