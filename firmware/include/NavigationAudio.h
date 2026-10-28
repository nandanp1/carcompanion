#pragma once
#include "Board.h"
#include "NavigationVoicePolicy.h"
#include "esp_codec_dev.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include <atomic>

class NavigationAudio {
public:
    bool init(Board&,uint8_t volume);
    void update(const DisplayState&,uint64_t now);
    void setVolume(uint8_t);
    uint8_t volume() const { return volume_.load(); }
    bool ready() const { return ready_; }
    bool speaking() const { return speaking_.load(); }
    void test();
private:
    static void run(void*);
    bool play(Voice::Clip,uint32_t generation);
    void enqueue(Voice::Prompt);
    void cancel();
    esp_codec_dev_handle_t codec_=nullptr;
    QueueHandle_t queue_=nullptr;
    std::atomic<uint8_t> volume_{50};
    std::atomic<uint32_t> generation_{0};
    std::atomic<bool> speaking_{false}, manualTest_{false};
    bool ready_=false, wasUsable_=false;
    std::string lastStepKey_;
    uint32_t lastStep_=0;
    Voice::Policy policy_;
};
