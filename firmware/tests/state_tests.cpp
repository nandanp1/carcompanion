#include "DisplayState.h"
#include "Protocol.h"
#include "JpegBuffer.h"
#include "ArtworkBuffer.h"
#include <cassert>
#include <cstring>
#include <cstdio>
namespace {
unsigned rgbAllocations=0;
bool failRgbAllocation=false;
void* testRgbAllocate(size_t bytes) { ++rgbAllocations; return failRgbAllocation?nullptr:std::malloc(bytes); }
}
int main() {
    // A replacement cover must reuse the full-size allocation even when the
    // allocator cannot provide any new contiguous block (observed on C6).
    {
        ArtworkBuffer displayOwned;
        assert(displayOwned.ensure(240*240*2,testRgbAllocate));
        auto* original=displayOwned.data(); original[0]=0x5a;
        assert(rgbAllocations==1 && displayOwned.capacity()==115200);
        ArtworkBuffer decoderOwned=std::move(displayOwned);
        assert(!displayOwned.data() && displayOwned.capacity()==0);
        failRgbAllocation=true;
        assert(decoderOwned.ensure(115200,testRgbAllocate));
        assert(decoderOwned.ensure(28800,testRgbAllocate));
        assert(decoderOwned.data()==original && decoderOwned.data()[0]==0x5a && rgbAllocations==1);
        assert(!decoderOwned.ensure(ArtworkBuffer::MaximumBytes+1,testRgbAllocate));
        displayOwned=std::move(decoderOwned);
        assert(displayOwned.data()==original && !decoderOwned.data());
        assert(displayOwned.capacity()==115200); // preserve capacity after a smaller image
    }
    {
        failRgbAllocation=false;
        ArtworkBuffer output; assert(output.ensure(28800,testRgbAllocate));
        failRgbAllocation=true;
        assert(!output.ensure(115200,testRgbAllocate));
        assert(!output.data() && output.capacity()==0); // failed growth has no dangling owner
        failRgbAllocation=false;
        assert(output.ensure(28800,testRgbAllocate)); // bounded half-scale recovery
        ArtworkBuffer failedDecode=std::move(output);
        assert(!output.data()); // failed decoder scope frees exactly once under ASan
    }
    PageNavigation pages;
    assert(pages.visible()==Page::Driving);
    assert(!pages.swipe(-40,0,100)); assert(!pages.swipe(-90,70,100)); assert(!pages.swipe(-90,0,1000));
    assert(pages.swipe(-90,2,200)); assert(pages.visible()==Page::Music);
    pages.navigation(true); assert(pages.visible()==Page::Navigation);
    pages.select(Page::Driving); pages.navigation(true); assert(pages.visible()==Page::Driving);
    pages.navigation(false); assert(pages.visible()==Page::Music);
    pages.select(Page::Driving); assert(pages.swipe(90,0,200)); assert(pages.visible()==Page::Navigation);
    DisplayState s; assert(!s.speedAvailable(1000)); s.connected=true; s.gpsValid=true;
    s.drivingReceivedMs=1000; s.gpsAgeMs=100; assert(s.speedAvailable(3899)); assert(!s.speedAvailable(3900));
    assert(!s.limitAvailable(1000)); s.speedLimitMph=55; s.limitConfidence=0.79; s.limitAgeMs=0;
    assert(!s.limitAvailable(1000)); s.limitConfidence=0.8; assert(s.limitAvailable(1000)); assert(!s.limitAvailable(31000));
    s.connected=false; assert(!s.speedAvailable(1000)); assert(!s.limitAvailable(1000));
    const uint8_t input[]="123456789"; assert(Protocol::crc32(input,9)==0xcbf43926u);
    uint8_t buffer[1024]={}; Protocol::Receiver receiver(buffer,sizeof(buffer));
    const uint8_t first[]={1,1,1,0,0,0,0,0,14,0,0,0,0x7b,0x22,0x6f,0x70,0x22,0x3a,0x22,0x68};
    const uint8_t last[]={1,2,1,0,8,0,0,0,14,0,0,0,0x65,0x6c,0x6c,0x6f,0x22,0x7d};
    assert(receiver.accept(first,sizeof(first))==Protocol::Result::Accepted);
    assert(receiver.accept(first,sizeof(first))==Protocol::Result::Accepted);
    assert(receiver.accept(last,sizeof(last))==Protocol::Result::Complete);
    assert(receiver.completedNow());
    assert(receiver.accept(last,sizeof(last))==Protocol::Result::Complete);
    assert(!receiver.completedNow());
    assert(receiver.accept(first,sizeof(first))==Protocol::Result::Complete);
    assert(!receiver.completedNow());
    assert(!memcmp(buffer,"{\"op\":\"hello\"}",14));
    receiver.reset(); assert(receiver.accept(last,sizeof(last))==Protocol::Result::Invalid);
    uint8_t art[32]; Protocol::Receiver artwork(art,sizeof(art),false); artwork.expect(1,14);
    assert(artwork.accept(first,sizeof(first))==Protocol::Result::Accepted);
    uint8_t bad[sizeof(last)]; memcpy(bad,last,sizeof(last)); bad[4]=10;
    assert(artwork.accept(bad,sizeof(bad))==Protocol::Result::Invalid);
    artwork.reset(); assert(artwork.accept(last,sizeof(last))==Protocol::Result::Invalid);
    JpegBuffer jpeg; jpeg.input=input; jpeg.size=9; uint8_t copied[10]={};
    assert(jpeg.read(copied,3)==3); assert(!memcmp(copied,"123",3));
    assert(jpeg.read(nullptr,1000)==6); assert(jpeg.read(copied,1000)==0); assert(jpeg.offset==9);
    uint16_t pixels[4]={}; const uint8_t colors[]={255,0,0,0,255,0,0,0,255,255,255,255};
    jpeg.output=pixels; jpeg.width=jpeg.height=2;
    assert(jpeg.writeRgb888(colors,0,0,1,1));
    assert(pixels[0]==0xf800 && pixels[1]==0x07e0 && pixels[2]==0x001f && pixels[3]==0xffff);
    assert(!jpeg.writeRgb888(colors,0,0,2,1)); assert(!jpeg.writeRgb888(colors,1,0,0,1));
    const std::string nested=std::string(500,'[')+std::string(500,']');
    assert(!Protocol::validJsonText(reinterpret_cast<const uint8_t*>(nested.data()),nested.size()));
    const std::string quoted=R"({"title":"brackets [[[[[[[[[[[[ and escaped \" quote"})";
    assert(Protocol::validJsonText(reinterpret_cast<const uint8_t*>(quoted.data()),quoted.size()));
    const uint8_t invalidUtf8[]={0xc0,0x80}; assert(!Protocol::validJsonText(invalidUtf8,sizeof(invalidUtf8)));
    const uint8_t badSurrogate[]={0xed,0xa0,0x80}; assert(!Protocol::validJsonText(badSurrogate,sizeof(badSurrogate)));
    puts("Firmware state, gesture, stale data, CRC, duplicate, interruption and bounded JPEG callback checks passed.");
}
