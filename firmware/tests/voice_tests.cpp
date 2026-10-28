#include "NavigationVoicePolicy.h"
#include "ButtonDebouncer.h"
#include <cassert>
#include <cstdio>

static DisplayState sample(uint64_t now,const char* distance="500 ft") {
    DisplayState s; s.connected=true; s.navigationActive=true; s.navigationSdk=true;
    s.navigationReceivedMs=now; s.maneuver="right"; s.road="Example Road";
    s.distance=distance; s.navigationStep=1; return s;
}
int main() {
    using Voice::Clip;
    Voice::Policy policy;
    auto s=sample(1000); auto prompt=policy.update(s,1000);
    assert(prompt && prompt->prefix==Clip::Feet500 && prompt->maneuver==Clip::Right);
    assert(!policy.update(s,1100));
    s.navigationReceivedMs=8000; assert(!policy.update(s,8000)); // unchanged update doesn't repeat speech
    s.distance="200 ft"; prompt=policy.update(s,8000);
    assert(prompt && prompt->prefix==Clip::Feet200);
    s.distance="30 ft"; s.navigationReceivedMs=8100; prompt=policy.update(s,8100);
    assert(prompt && prompt->prefix==Clip::Now); // immediate cue isn't delayed by cooldown
    s.distance="100 ft"; assert(!policy.update(s,8200)); // GPS jitter doesn't reannounce approach
    s.navigationStep=2; s.distance="500 ft"; prompt=policy.update(s,8300);
    assert(prompt && prompt->maneuver==Clip::Right); // successive right turns on the same road
    s.connected=false; assert(!policy.update(s,8400));
    s=sample(9000); s.demo=true; assert(!policy.update(s,9000));
    s.demo=false; s.navigationSdk=false; assert(!policy.update(s,9000)); // old notification source never speaks
    s.navigationSdk=true; assert(!policy.update(s,14000)); // stale data
    s=sample(15000); s.maneuver="unknown"; assert(!policy.update(s,15000));
    s.road="Rerouting…"; prompt=policy.update(s,15000);
    assert(prompt && prompt->maneuver==Clip::Rerouting);
    assert(!policy.update(s,15100));
    s.road="Example Road"; s.maneuver="left"; prompt=policy.update(s,15200);
    assert(prompt && prompt->maneuver==Clip::Left);
    s.navigationActive=false; assert(!policy.update(s,15300));
    assert(Voice::Policy::meters("nan ft")<0);
    assert(Voice::Policy::meters("-1 mi")<0);
    assert(Voice::Policy::meters("500 feetx")<0);
    assert(Voice::Policy::meters("1 km")==1000);
    assert(Voice::Policy::turn("ferry")==Clip::None);
    ButtonDebouncer button;
    assert(!button.poll(true,100)); assert(!button.poll(false,110)); assert(!button.poll(true,120));
    assert(!button.poll(true,149)); assert(button.poll(true,150));
    assert(!button.poll(true,649)); assert(button.poll(true,650));
    assert(!button.poll(true,700)); assert(button.poll(true,800));
    assert(!button.poll(false,810)); assert(!button.poll(false,850));
    assert(!button.poll(true,900)); assert(button.poll(true,930));
    std::puts("PASS: voice thresholds, cooldown, step identity, rerouting, stale/disconnected/demo guards, button debounce and hold repeat");
}
