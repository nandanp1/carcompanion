#pragma once
#include <cstdint>

/** Debounced press with bounded hold repeat. Only one caller polls each button. */
class ButtonDebouncer {
public:
    bool poll(bool down,uint64_t now) {
        if(down!=raw_) { raw_=down; changed_=now; }
        if(raw_!=stable_ && now-changed_>=30) {
            stable_=raw_;
            if(stable_) { nextRepeat_=now+500; return true; }
        }
        if(stable_ && raw_ && now>=nextRepeat_) { nextRepeat_=now+150; return true; }
        return false;
    }
private:
    bool raw_=false,stable_=false;
    uint64_t changed_=0,nextRepeat_=0;
};
