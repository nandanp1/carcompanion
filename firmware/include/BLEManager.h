#pragma once
#include "ArtworkReceiver.h"
#include "ConnectionStatusManager.h"
#include <atomic>
#include "host/ble_hs.h"
#include "host/ble_gap.h"
class BLEManager {
public:
    explicit BLEManager(DisplayManager& display):display_(display),connection_(display) {}
    void init();
    void playback(uint8_t command);
private:
    static int access(uint16_t,uint16_t,ble_gatt_access_ctxt*,void*);
    static int gap(ble_gap_event*,void*);
    static void sync();
    static void host(void*);
    void advertise();
    void status(uint8_t event,uint8_t code,uint16_t id,uint32_t offset);
    void hello(uint8_t* out);
    bool json(unsigned channel,const uint8_t* data,size_t count);
    DisplayManager& display_;
    ConnectionStatusManager connection_;
    ArtworkReceiver artwork_;
    std::atomic<uint16_t> connectionHandle_{BLE_HS_CONN_HANDLE_NONE};
    std::atomic<bool> encrypted_{false};
    uint16_t controlHandle_=0,playbackHandle_=0;
    std::atomic<uint16_t> playbackSequence_{0};
    uint8_t ownAddressType_=0;
    std::array<std::array<uint8_t,Protocol::JsonLimit+1>,4> jsonBytes_{};
    bool jsonValid_[4]={};
    Protocol::Receiver receivers_[4]={
        {jsonBytes_[0].data(),Protocol::JsonLimit},{jsonBytes_[1].data(),Protocol::JsonLimit},
        {jsonBytes_[2].data(),Protocol::JsonLimit},{jsonBytes_[3].data(),Protocol::JsonLimit}};
};
