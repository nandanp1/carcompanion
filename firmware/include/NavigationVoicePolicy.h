#pragma once
#include "DisplayState.h"
#include <cmath>
#include <cstdlib>
#include <optional>

namespace Voice {
enum class Clip : uint8_t { None, Ahead, Mile, HalfMile, QuarterMile, Feet500, Feet200, Now,
    Straight, Left, Right, SlightLeft, SlightRight, UTurn, Roundabout, Arrive, Rerouting, Test };
struct Prompt { Clip prefix=Clip::None, maneuver=Clip::None; uint32_t generation=0; };

// Only the display task calls this policy. Playback runs separately and can be cancelled.
class Policy {
public:
    static double meters(const std::string& text) {
        char* end=nullptr; const double value=std::strtod(text.c_str(),&end);
        if(end==text.c_str() || !std::isfinite(value) || value<0) return -1;
        while(*end==' ') ++end;
        const std::string unit=end;
        if(unit=="ft") return value*0.3048;
        if(unit=="mi") return value*1609.344;
        if(unit=="m") return value;
        if(unit=="km") return value*1000;
        return -1;
    }
    static Clip turn(const std::string& value) {
        if(value=="left") return Clip::Left;
        if(value=="right") return Clip::Right;
        if(value=="slight_left") return Clip::SlightLeft;
        if(value=="slight_right") return Clip::SlightRight;
        if(value=="straight") return Clip::Straight;
        if(value=="uturn") return Clip::UTurn;
        if(value=="roundabout") return Clip::Roundabout;
        if(value=="arrive") return Clip::Arrive;
        return Clip::None;
    }
    static bool usable(const DisplayState& s,uint64_t now) {
        return s.connected && !s.demo && s.navigationActive && s.navigationSdk &&
            now>=s.navigationReceivedMs && now-s.navigationReceivedMs<5000;
    }
    std::optional<Prompt> update(const DisplayState& s,uint64_t now) {
        if(!usable(s,now)) { reset(); return {}; }
        if(s.road=="Rerouting…") {
            const bool first=!rerouting_; reset(); rerouting_=true;
            if(first) return Prompt{Clip::None,Clip::Rerouting};
            return {};
        }
        const Clip maneuver=turn(s.maneuver);
        if(maneuver==Clip::None) { reset(); return {}; }
        const double distance=meters(s.distance);
        const std::string key=s.maneuver+"\n"+s.road;
        const bool newStep=key!=key_ || (s.navigationStep && s.navigationStep!=step_) || rerouting_ ||
            (distance>=0 && lastDistance_>=0 && distance>lastDistance_+100 && distance>lastDistance_*1.5);
        if(newStep) { key_=key; step_=s.navigationStep; bucket_=-1; rerouting_=false; }
        lastDistance_=distance;
        int bucket=0; Clip prefix=Clip::Ahead;
        if(maneuver==Clip::Arrive) { bucket=7; prefix=Clip::None; }
        else if(distance>=0) {
            constexpr double thresholds[]={1609.344,804.672,402.336,152.4,60.96,20};
            constexpr Clip clips[]={Clip::Mile,Clip::HalfMile,Clip::QuarterMile,Clip::Feet500,Clip::Feet200,Clip::Now};
            for(int i=0;i<6;++i) if(distance<=thresholds[i]) {
                bucket=i+1; prefix=(i==5 || distance>=thresholds[i]*0.8)?clips[i]:Clip::Ahead;
            }
        }
        if(bucket<=bucket_) return {};
        // Don't chatter around thresholds, but never delay the immediate turn cue.
        if(!newStep && bucket!=6 && now-lastSpoken_<6000) return {};
        bucket_=bucket; lastSpoken_=now;
        return Prompt{prefix,maneuver};
    }
    void reset() { key_.clear(); step_=0; bucket_=-1; lastDistance_=-1; rerouting_=false; }
private:
    std::string key_;
    uint32_t step_=0;
    int bucket_=-1;
    double lastDistance_=-1;
    bool rerouting_=false;
    uint64_t lastSpoken_=0;
};
}
