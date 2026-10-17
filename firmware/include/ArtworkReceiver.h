#pragma once
#include "Protocol.h"
#include "DisplayManager.h"
#include "freertos/task.h"
#include <atomic>
#include <functional>
class ArtworkReceiver {
public:
    enum Status : uint8_t { OK,READY,COMPLETE,CACHED,BAD_OFFSET,BAD_CRC,INVALID,BUSY,DECODE_FAILED };
    void init(DisplayManager&,std::function<void(uint8_t,uint16_t,uint32_t)> status);
    void begin(uint16_t id,uint32_t size,uint32_t crc);
    bool fragment(const uint8_t*,size_t);
    void commit(uint16_t id);
    void abort(uint16_t id);
    void disconnected();
    uint32_t cachedCrc() const { return cached_.load(); }
private:
    static void work(void*);
    void decode();
    std::array<uint8_t,Protocol::ArtLimit> compressed_{};
    Protocol::Receiver receiver_{compressed_.data(),compressed_.size(),false};
    SemaphoreHandle_t mutex_=nullptr;
    TaskHandle_t task_=nullptr;
    DisplayManager* display_=nullptr;
    std::function<void(uint8_t,uint16_t,uint32_t)> status_;
    std::atomic<uint32_t> cached_{0};
    uint32_t expectedCrc_=0;
    bool busy_=false;
};
