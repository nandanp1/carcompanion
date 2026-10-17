#pragma once
#include "DisplayManager.h"
class ConnectionStatusManager {
public:
    explicit ConnectionStatusManager(DisplayManager& display):display_(display){}
    void changed(bool connected) { display_.connected(connected); }
private:
    DisplayManager& display_;
};
