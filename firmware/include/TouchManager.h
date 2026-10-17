#pragma once
#include "Board.h"
#include "DisplayState.h"
#include "lvgl.h"
#include <functional>
class TouchManager {
public:
    void init(Board& board,lv_display_t* display,std::function<void(int,int,uint32_t)> swipe);
private:
    static void read(lv_indev_t*,lv_indev_data_t*);
    Board* board_=nullptr;
    bool pressed_=false;
    int x_=0,y_=0,lastX_=0,lastY_=0;
    uint32_t downAt_=0;
    std::function<void(int,int,uint32_t)> swipe_;
};
