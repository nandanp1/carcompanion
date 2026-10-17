#pragma once
#include <cstdint>
#include <array>
#include <string>
#include <algorithm>

enum class Page : uint8_t { Navigation=0, Driving=1, Music=2 };
struct DisplayState {
    bool connected=false, demo=false, gpsValid=false, navigationActive=false, playing=false, artworkPresent=false;
    float speedMph=0, speedLimitMph=-1, limitConfidence=0;
    uint32_t gpsAgeMs=UINT32_MAX, limitAgeMs=UINT32_MAX;
    uint64_t drivingReceivedMs=0, musicReceivedMs=0;
    std::string clock="--:--";
    std::string title="No music", artist="Connect your phone", album, distance, road, eta;
    std::string maneuver="unknown", navigationStatus="No active route";
    uint32_t positionMs=0, durationMs=0, artworkId=0;
    bool speedAvailable(uint64_t now) const {
        return (connected || demo) && gpsValid && now>=drivingReceivedMs && gpsAgeMs<3000 && now-drivingReceivedMs<3000-gpsAgeMs;
    }
    bool limitAvailable(uint64_t now) const {
        return (connected || demo) && speedLimitMph>=1 && limitConfidence>=0.8f && limitAgeMs<30000 &&
            now>=drivingReceivedMs && now-drivingReceivedMs<30000-limitAgeMs;
    }
};

// Auto-navigation overrides the visible page once per route. Manual swipes remain
// possible; the original user choice is restored when that route ends.
class PageNavigation {
public:
    explicit PageNavigation(Page saved=Page::Driving): selected_(saved), visible_(saved) {}
    Page visible() const { return visible_; }
    Page selected() const { return selected_; }
    void select(Page page) { visible_=selected_=page; }
    void navigation(bool active) {
        if(active && !active_) { before_=selected_; visible_=Page::Navigation; }
        else if(!active && active_) { visible_=selected_=before_; }
        active_=active;
    }
    bool swipe(int dx,int dy,uint32_t durationMs) {
        if(std::abs(dx)<70 || std::abs(dx)<std::abs(dy)*2 || durationMs>900) return false;
        int index=static_cast<int>(visible_)+(dx<0?1:-1);
        if(index<0 || index>2) return false;
        select(static_cast<Page>(index)); return true;
    }
private:
    Page selected_,visible_,before_=Page::Driving;
    bool active_=false;
};
