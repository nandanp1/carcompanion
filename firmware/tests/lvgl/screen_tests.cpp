#include "Screens.h"
#include "src/misc/cache/instance/lv_image_cache.h"
#include <cassert>
#include <cstdio>
#include <vector>
#include <cstring>

static uint32_t now=0;
static uint32_t ticks() { return now; }
static std::vector<uint16_t> frame(480*480);
static bool pending=false;
static lv_area_t pendingArea{};
static uint8_t* pendingPixels=nullptr;
static std::vector<uint8_t> submitted;
static unsigned flushes=0;
static void flush(lv_display_t*,const lv_area_t* area,uint8_t* pixels) {
    assert(!pending); // LVGL must wait before submitting another DMA transfer.
    pending=true; pendingArea=*area; pendingPixels=pixels;
    const size_t count=lv_area_get_size(area)*2;
    submitted.assign(pixels,pixels+count);
    ++flushes;
}
static void waitFlush(lv_display_t*) {
    assert(pending);
    // Deferred copying emulates DMA still reading this buffer while LVGL
    // renders the other stripe. Any premature reuse is detectable here.
    assert(std::memcmp(pendingPixels,submitted.data(),submitted.size())==0);
    const unsigned width=lv_area_get_width(&pendingArea);
    for(int y=pendingArea.y1;y<=pendingArea.y2;++y) {
        assert(y>=0 && y<480 && pendingArea.x1>=0 && pendingArea.x2<480);
        std::memcpy(frame.data()+y*480+pendingArea.x1,
            pendingPixels+(y-pendingArea.y1)*width*2,width*2);
    }
    pending=false;
}
static void render(lv_display_t* display) {
    now+=20;
    lv_timer_handler();
    lv_refr_now(display);
    // Consume the asynchronous final stripe through LVGL's own wait path.
    now+=20;
    lv_timer_handler();
    lv_refr_now(display);
    if(pending) {
        waitFlush(display);
        lv_display_flush_ready(display);
    }
}
static void save(const char* name) {
    auto* f=std::fopen(name,"wb"); assert(f);
    std::fprintf(f,"P6\n480 480\n255\n");
    for(uint16_t pixel:frame) {
        const unsigned char rgb[]={static_cast<unsigned char>(((pixel>>11)&31)*255/31),
            static_cast<unsigned char>(((pixel>>5)&63)*255/63),static_cast<unsigned char>((pixel&31)*255/31)};
        std::fwrite(rgb,1,3,f);
    }
    std::fclose(f);
}
static void expectPixel(int x,int y,uint16_t color) {
    const auto actual=frame[y*480+x];
    if(actual!=color) std::fprintf(stderr,"pixel %d,%d expected %04x actual %04x\n",x,y,color,actual);
    assert(actual==color);
}
static lv_obj_t* labelAt(lv_obj_t* page,int y) {
    for(unsigned i=0;i<lv_obj_get_child_count(page);++i) {
        auto* child=lv_obj_get_child(page,i);
        if(lv_obj_check_type(child,&lv_label_class) && lv_obj_get_y(child)==y) return child;
    }
    assert(false && "Expected screen label not found"); return nullptr;
}
static void expectSingleLine(lv_obj_t* label,int nextRow) {
    const int height=lv_obj_get_height(label);
    assert(height==lv_font_get_line_height(lv_obj_get_style_text_font(label,LV_PART_MAIN)));
    assert(lv_obj_get_y(label)+height<nextRow);
    std::printf("Single-line label y=%d height=%d text=%s\n",int(lv_obj_get_y(label)),height,lv_label_get_text(label));
}
int main() {
    lv_init(); lv_tick_set_cb(ticks);
    lv_log_register_print_cb([](lv_log_level_t,const char* text) { std::fputs(text,stderr); });
    auto* display=lv_display_create(480,480);
    lv_display_set_color_format(display,LV_COLOR_FORMAT_RGB565);
    alignas(16) static uint8_t stripes[480*20*2];
    lv_display_set_buffers(display,stripes,stripes+480*10*2,480*10*2,LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_flush_cb(display,flush); lv_display_set_flush_wait_cb(display,waitFlush);
    NavigationScreen navigation; DrivingScreen driving; MusicScreen music;
    auto* navigationPage=navigation.create(); auto* drivingPage=driving.create(); auto* page=music.create([](uint8_t){});
    lv_screen_load(page);
    DisplayState state; state.connected=true; state.title="Artwork render smoke"; state.artist="Production MusicScreen";
    state.album="Pinned LVGL 9.5.0"; state.playing=true; state.durationMs=180000;
    music.update(state,now,0,false); render(display); save("placeholder.ppm");
    const uint16_t placeholder=frame[100*480+160];
    const uint16_t placeholderCenter=frame[140*480+200];
    std::vector<uint16_t> pixels(240*240);
    for(unsigned y=0;y<240;++y) for(unsigned x=0;x<240;++x)
        pixels[y*240+x]=y<120?(x<120?0xf800:0x07e0):(x<120?0x001f:0xffff);
    lv_image_dsc_t image{}; image.header.magic=LV_IMAGE_HEADER_MAGIC; image.header.cf=LV_COLOR_FORMAT_RGB565;
    image.header.w=240; image.header.h=240; image.header.stride=480;
    image.data_size=240*240*2; image.data=reinterpret_cast<const uint8_t*>(pixels.data());
    constexpr uint32_t crc=0x33445566;
    state.artworkPresent=true; state.artworkId=crc;
    setScreenArtwork(driving,navigation,music,&image); music.update(state,now,crc,true); render(display); save("matching-artwork.ppm");
    expectPixel(160,100,0xf800); expectPixel(280,100,0x07e0);
    expectPixel(160,220,0x001f); expectPixel(280,220,0xffff);
    assert(frame[100*480+160]!=placeholder);
    state.playing=false;
    state.title="A very long song title featuring the extended midnight driving edition and the complete original studio recording";
    state.artist="An exceptionally long artist name with several featured collaborators and a full orchestra";
    state.album="The complete collection of every recording from an unusually long album title";
    music.update(state,now,crc,true); render(display); save("long-metadata.ppm");
    const auto title=labelAt(page,310),artist=labelAt(page,345),album=labelAt(page,374);
    expectSingleLine(title,345); expectSingleLine(artist,374); expectSingleLine(album,405);
    for(auto* label:{title,artist,album}) assert(std::strstr(lv_label_get_text(label),"...")!=nullptr);
    expectPixel(160,100,0xf800); // Long labels do not disturb decoded artwork.
    const unsigned beforeIdle=flushes;
    for(int i=0;i<20;++i) { music.update(state,now,crc,true); render(display); }
    assert(flushes==beforeIdle); // Ellipsis must not cause the same text to redraw every tick.
    state.artist="First artist\r\nSecond artist\t & friends";
    music.update(state,now,crc,true); render(display);
    assert(!std::strchr(lv_label_get_text(artist),'\n') && !std::strchr(lv_label_get_text(artist),'\r'));
    expectSingleLine(artist,374);
    state.title="Short title"; state.artist=""; state.album="";
    music.update(state,now,crc,true); render(display);
    assert(std::strcmp(lv_label_get_text(title),"Short title")==0);
    assert(std::strcmp(lv_label_get_text(artist),"")==0 && std::strcmp(lv_label_get_text(album),"")==0);
    state.title="A very long driving dashboard song title that continues far beyond the available space";
    state.artist="An unusually long artist name with many featured performers";
    state.navigationActive=true; state.maneuver="right"; state.distance="500 ft"; state.eta="ETA 10:45";
    state.road="A very long upcoming road name through the historic downtown neighborhood and coastal highway";
    state.navigationStatus="From phone notification";
    const auto updateAll=[&](uint32_t cached,bool valid) {
        driving.update(state,now,cached,valid);
        navigation.update(state,now,cached,valid);
        music.update(state,now,cached,valid);
    };
    const auto thumbnailPixels=[] {
        expectPixel(114,388,0xf800); expectPixel(144,388,0x07e0);
        expectPixel(114,418,0x001f); expectPixel(144,418,0xffff);
    };
    const auto imageOn=[](lv_obj_t* root) -> lv_obj_t* {
        for(unsigned i=0;i<lv_obj_get_child_count(root);++i) {
            auto* child=lv_obj_get_child(root,i);
            if(lv_obj_check_type(child,&lv_image_class)) return child;
        }
        assert(false && "screen artwork widget missing"); return nullptr;
    };
    updateAll(crc,true);
    for(auto* root:{drivingPage,navigationPage}) {
        lv_screen_load(root); render(display); thumbnailPixels();
        auto* thumbnail=imageOn(root);
        assert(lv_image_get_src(thumbnail)==&image); // all widgets share one descriptor/allocation
        assert(lv_obj_get_width(thumbnail)==52 && lv_obj_get_height(thumbnail)==52);
        lv_area_t area{}; lv_obj_get_coords(thumbnail,&area);
        for(int x:{area.x1,area.x2}) for(int y:{area.y1,area.y2})
            assert((x-240)*(x-240)+(y-240)*(y-240)<240*240);
        for(int y:{381,406}) {
            auto* text=labelAt(root,y); expectSingleLine(text,y==381?406:430);
            assert(lv_obj_get_x(text)==172 && lv_obj_get_width(text)==212);
            assert(std::strstr(lv_label_get_text(text),"...")!=nullptr);
        }
        auto* road=labelAt(navigationPage,250);
        assert(lv_obj_get_height(road)==54 && lv_obj_get_y(road)+lv_obj_get_height(road)<310);
        save(root==drivingPage?"driving-thumbnail.ppm":"navigation-thumbnail.ppm");
        const unsigned before=flushes;
        for(int i=0;i<20;++i) { updateAll(crc,true); render(display); }
        assert(flushes==before); // neither thumbnail scaling nor ellipsis causes idle redraws
    }
    state.artworkId=crc+1; updateAll(crc,true);
    for(auto* root:{drivingPage,navigationPage,page}) {
        lv_screen_load(root); render(display);
        assert(lv_obj_has_flag(imageOn(root),LV_OBJ_FLAG_HIDDEN));
        if(root!=page) {
            assert(lv_obj_get_x(labelAt(root,381))==104 && lv_obj_get_width(labelAt(root,381))==280);
            assert(frame[418*480+114]!=0x001f);
        }
    }
    assert(frame[100*480+160]==placeholder);
    state.artworkPresent=false; state.artworkId=crc; updateAll(crc,true);
    for(auto* root:{drivingPage,navigationPage,page}) assert(lv_obj_has_flag(imageOn(root),LV_OBJ_FLAG_HIDDEN));
    state.artworkPresent=true; state.artworkId=0; updateAll(0,true);
    for(auto* root:{drivingPage,navigationPage,page}) {
        lv_screen_load(root); render(display);
        if(root==page) expectPixel(160,100,0xf800); else thumbnailPixels();
    }
    // The production detach helper must release every LVGL reference before
    // the decoder can overwrite/free the one shared output allocation.
    setScreenArtwork(driving,navigation,music,nullptr); lv_image_cache_drop(&image);
    for(auto* root:{drivingPage,navigationPage,page}) {
        assert(lv_image_get_src(imageOn(root))==nullptr);
        assert(lv_obj_has_flag(imageOn(root),LV_OBJ_FLAG_HIDDEN));
    }
    std::vector<uint16_t>().swap(pixels); // ASan catches stale reads from any page.
    image.data=nullptr; image.data_size=0; updateAll(0,false);
    for(auto* root:{drivingPage,navigationPage,page}) { lv_screen_load(root); render(display); }
    expectPixel(200,140,placeholderCenter);
    pixels.resize(120*120,0x07e0);
    image.header.w=120; image.header.h=120; image.header.stride=240; image.data_size=120*120*2;
    image.data=reinterpret_cast<const uint8_t*>(pixels.data());
    setScreenArtwork(driving,navigation,music,&image); updateAll(0,true);
    for(auto* root:{drivingPage,navigationPage,page}) {
        lv_screen_load(root); render(display);
        if(root==page) { expectPixel(200,140,0x07e0); expectPixel(160,100,placeholder); }
        else { expectPixel(114,388,0x07e0); expectPixel(144,418,0x07e0); }
    }
    save("half-scale-artwork.ppm");
    setScreenArtwork(driving,navigation,music,nullptr); lv_image_cache_drop(&image);
    updateAll(0,false); render(display);
    VolumeOverlay volume;
    volume.create(lv_layer_top());
    volume.show(50,true,false); render(display); save("volume-overlay.ppm");
    auto* overlay=lv_obj_get_child(lv_layer_top(),lv_obj_get_child_count(lv_layer_top())-1);
    assert(!lv_obj_has_flag(overlay,LV_OBJ_FLAG_HIDDEN));
    assert(lv_obj_get_x(overlay)==100 && lv_obj_get_width(overlay)==280);
    volume.show(0,true,false); render(display); save("volume-muted.ppm");
    auto* value=labelAt(overlay,36); assert(std::string(lv_label_get_text(value))=="MUTED");
    volume.hide(); render(display); assert(lv_obj_has_flag(overlay,LV_OBJ_FLAG_HIDDEN));
    lv_mem_monitor_t memory{}; lv_mem_monitor(&memory);
    assert(memory.max_used<48*1024);
    std::printf("PASS:3-screen shared artwork, thumbnail pixels, circle bounds, detach/free/replacement, CRC guards, metadata/no redraw, %u safe DMA stripes; LVGL used=%zu free=%zu peak=%zu\n",flushes,memory.total_size-memory.free_size,memory.free_size,memory.max_used);
    lv_deinit();
}
