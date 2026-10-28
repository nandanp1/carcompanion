#include "BLEManager.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "store/config/ble_store_config.h"
#include "cJSON.h"
#include "esp_timer.h"
#include "esp_log.h"
#include "esp_heap_caps.h"
#include "host/ble_att.h"
#include <cstring>
#include <cmath>
#include <memory>
extern "C" void ble_store_config_init(void);

namespace {
BLEManager* instance=nullptr;
// NimBLE byte arrays are least-significant byte first.
const ble_uuid128_t serviceUuid=BLE_UUID128_INIT(0,0,0x0a,0x6c,0x98,0x7b,0x2a,0x9c,0x7d,0x4b,0x7e,0x5a,0,0,0x60,0x98);
ble_uuid128_t characteristicUuids[6];
ble_gatt_chr_def characteristics[7]={};
ble_gatt_svc_def services[2]={};
uint64_t nowMs() { return esp_timer_get_time()/1000; }
void logLink(uint16_t handle,const char* event) {
    ble_gap_conn_desc desc={};
    if(ble_gap_conn_find(handle,&desc)==0) ESP_LOGI("BLE","%s handle=%u mtu=%u interval=%u*1.25ms latency=%u timeout=%u*10ms encrypted=%u bonded=%u heap=%u largest=%u",
        event,handle,ble_att_mtu(handle),desc.conn_itvl,desc.conn_latency,desc.supervision_timeout,
        desc.sec_state.encrypted,desc.sec_state.bonded,heap_caps_get_free_size(MALLOC_CAP_8BIT),heap_caps_get_largest_free_block(MALLOC_CAP_8BIT));
}
const cJSON* item(const cJSON* root,const char* key) { return cJSON_GetObjectItemCaseSensitive(root,key); }
bool textField(const cJSON* root,const char* key,std::string& out,size_t max,bool nullable=true) {
    const auto* value=item(root,key);
    if(!value || cJSON_IsNull(value)) { out.clear(); return nullable; }
    if(!cJSON_IsString(value) || !value->valuestring || strlen(value->valuestring)>max) return false;
    out=value->valuestring; return true;
}
bool number(const cJSON* root,const char* key,double& out,double minimum,double maximum,bool nullable=false) {
    const auto* value=item(root,key);
    if(!value || cJSON_IsNull(value)) { out=-1; return nullable; }
    if(!cJSON_IsNumber(value) || !std::isfinite(value->valuedouble) || value->valuedouble<minimum || value->valuedouble>maximum) return false;
    out=value->valuedouble; return true;
}
bool integer(const cJSON* root,const char* key,double& out,double minimum,double maximum,bool nullable=false) {
    return number(root,key,out,minimum,maximum,nullable) && (out==-1 || std::floor(out)==out);
}
bool boolean(const cJSON* root,const char* key,bool& out) {
    const auto* value=item(root,key); if(!cJSON_IsBool(value)) return false;
    out=cJSON_IsTrue(value); return true;
}

}

void BLEManager::init() {
    instance=this;
    artwork_.init(display_,[this](uint8_t code,uint16_t id,uint32_t offset) { status(2,code,id,offset); });
    ESP_ERROR_CHECK(nimble_port_init()); ble_svc_gap_init(); ble_svc_gatt_init();
    ESP_ERROR_CHECK(ble_att_set_preferred_mtu(247));
    ble_hs_cfg.sync_cb=sync; ble_hs_cfg.sm_io_cap=BLE_HS_IO_NO_INPUT_OUTPUT;
    ble_hs_cfg.sm_bonding=1; ble_hs_cfg.sm_mitm=0; ble_hs_cfg.sm_sc=1;
    ble_hs_cfg.sm_our_key_dist=BLE_SM_PAIR_KEY_DIST_ENC|BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist=BLE_SM_PAIR_KEY_DIST_ENC|BLE_SM_PAIR_KEY_DIST_ID;
    ble_store_config_init();
    ESP_ERROR_CHECK(ble_svc_gap_device_name_set("Car Companion"));
    for(unsigned index=0;index<6;index++) {
        characteristicUuids[index]=serviceUuid; characteristicUuids[index].value[12]=index+1;
        auto& chr=characteristics[index]; chr.uuid=&characteristicUuids[index].u; chr.access_cb=access;
        chr.arg=reinterpret_cast<void*>(static_cast<uintptr_t>(index));
        chr.flags=index==5?BLE_GATT_CHR_F_NOTIFY:(BLE_GATT_CHR_F_WRITE|BLE_GATT_CHR_F_WRITE_ENC);
        if(index==0) { chr.flags|=BLE_GATT_CHR_F_READ|BLE_GATT_CHR_F_READ_ENC|BLE_GATT_CHR_F_NOTIFY; chr.val_handle=&controlHandle_; }
        if(index==5) chr.val_handle=&playbackHandle_;
    }
    services[0].type=BLE_GATT_SVC_TYPE_PRIMARY; services[0].uuid=&serviceUuid.u; services[0].characteristics=characteristics;
    ESP_ERROR_CHECK(ble_gatts_count_cfg(services)); ESP_ERROR_CHECK(ble_gatts_add_svcs(services));
    nimble_port_freertos_init(host);
}
void BLEManager::host(void*) { nimble_port_run(); nimble_port_freertos_deinit(); }
void BLEManager::sync() {
    ESP_ERROR_CHECK(ble_hs_id_infer_auto(0,&instance->ownAddressType_)); instance->advertise();
}
void BLEManager::advertise() {
    ble_hs_adv_fields fields={}; fields.flags=BLE_HS_ADV_F_DISC_GEN|BLE_HS_ADV_F_BREDR_UNSUP;
    fields.uuids128=const_cast<ble_uuid128_t*>(&serviceUuid); fields.num_uuids128=1; fields.uuids128_is_complete=1;
    ESP_ERROR_CHECK(ble_gap_adv_set_fields(&fields));
    ble_hs_adv_fields scan={}; const char* name=ble_svc_gap_device_name();
    scan.name=reinterpret_cast<const uint8_t*>(name); scan.name_len=strlen(name); scan.name_is_complete=1;
    ESP_ERROR_CHECK(ble_gap_adv_rsp_set_fields(&scan));
    ble_gap_adv_params params={}; params.conn_mode=BLE_GAP_CONN_MODE_UND; params.disc_mode=BLE_GAP_DISC_MODE_GEN;
    const int rc=ble_gap_adv_start(ownAddressType_,nullptr,BLE_HS_FOREVER,&params,gap,this);
    if(rc) ESP_LOGE("BLE","advertising failed: %d",rc);
}
int BLEManager::gap(ble_gap_event* event,void* arg) {
    auto* self=static_cast<BLEManager*>(arg);
    switch(event->type) {
    case BLE_GAP_EVENT_CONNECT:
        ESP_LOGI("BLE","connection result=%d",event->connect.status);
        if(event->connect.status==0) {
            self->connectionHandle_=event->connect.conn_handle; self->playbackSequence_=0;
            self->connection_.changed(true);
            logLink(event->connect.conn_handle,"connected");
            const int security=ble_gap_security_initiate(event->connect.conn_handle);
            ESP_LOGI("BLE","security initiate=%d",security);
            // Matches the payload/time request in IDF's NimBLE throughput example.
            // The central still chooses negotiated link parameters and ATT MTU.
            const int dataLength=ble_gap_set_data_len(event->connect.conn_handle,251,2120);
            ESP_LOGI("BLE","request 251-byte link payload result=%d",dataLength);
        } else self->advertise();
        break;
    case BLE_GAP_EVENT_ENC_CHANGE: {
        ble_gap_conn_desc desc={};
        self->encrypted_=event->enc_change.status==0 && ble_gap_conn_find(event->enc_change.conn_handle,&desc)==0 && desc.sec_state.encrypted;
        ESP_LOGI("BLE","encryption change status=%d encrypted=%u",event->enc_change.status,self->encrypted_.load());
        logLink(event->enc_change.conn_handle,"security");
        break;
    }
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI("BLE","disconnect reason=%d (0x%x)",event->disconnect.reason,event->disconnect.reason);
        self->connectionHandle_=BLE_HS_CONN_HANDLE_NONE; self->encrypted_=false;
        for(auto& receiver:self->receivers_) receiver.reset();
        self->artwork_.disconnected(); self->connection_.changed(false); self->advertise(); break;
    case BLE_GAP_EVENT_ADV_COMPLETE: self->advertise(); break;
    case BLE_GAP_EVENT_MTU:
        ESP_LOGI("BLE","ATT MTU changed handle=%u channel=%u mtu=%u",event->mtu.conn_handle,event->mtu.channel_id,event->mtu.value);
        break;
    case BLE_GAP_EVENT_CONN_UPDATE:
        ESP_LOGI("BLE","connection update status=%d",event->conn_update.status);
        logLink(event->conn_update.conn_handle,"updated");
        break;
    case BLE_GAP_EVENT_DATA_LEN_CHG:
        ESP_LOGI("BLE","link payload tx=%u rx=%u bytes",event->data_len_chg.max_tx_octets,event->data_len_chg.max_rx_octets);
        break;
    case BLE_GAP_EVENT_SUBSCRIBE:
        ESP_LOGI("BLE","subscription handle=%u notify=%u",event->subscribe.attr_handle,event->subscribe.cur_notify);
        break;
    case BLE_GAP_EVENT_REPEAT_PAIRING:
        // Do not silently delete an existing trusted bond. Reset deliberately
        // with erase-flash when moving this display to another phone.
        return BLE_GAP_REPEAT_PAIRING_IGNORE;
    default: break;
    }
    return 0;
}
void BLEManager::hello(uint8_t* out) {
    memset(out,0,16); out[0]=1; out[1]=1; Protocol::put16(out+6,32); Protocol::put32(out+8,1); Protocol::put32(out+12,artwork_.cachedCrc());
}
void BLEManager::status(uint8_t event,uint8_t code,uint16_t id,uint32_t offset) {
    const uint16_t conn=connectionHandle_.load(); if(conn==BLE_HS_CONN_HANDLE_NONE || !encrypted_) return;
    uint8_t bytes[16]={1,event,code,0}; Protocol::put16(bytes+4,id); Protocol::put16(bytes+6,32);
    Protocol::put32(bytes+8,offset); Protocol::put32(bytes+12,artwork_.cachedCrc());
    auto* buffer=ble_hs_mbuf_from_flat(bytes,sizeof(bytes)); if(buffer) ble_gatts_notify_custom(conn,controlHandle_,buffer);
}
void BLEManager::playback(uint8_t command) {
    if(connectionHandle_==BLE_HS_CONN_HANDLE_NONE || !encrypted_ || command<1 || command>3) return;
    uint8_t bytes[4]={1,command,0,0}; Protocol::put16(bytes+2,++playbackSequence_);
    auto* buffer=ble_hs_mbuf_from_flat(bytes,sizeof(bytes)); if(buffer) ble_gatts_notify_custom(connectionHandle_,playbackHandle_,buffer);
}
int BLEManager::access(uint16_t,uint16_t,ble_gatt_access_ctxt* context,void* arg) {
    auto* self=instance; const unsigned channel=reinterpret_cast<uintptr_t>(arg);
    if(context->op==BLE_GATT_ACCESS_OP_READ_CHR && channel==0) {
        uint8_t bytes[16]; self->hello(bytes); return os_mbuf_append(context->om,bytes,16)?BLE_ATT_ERR_INSUFFICIENT_RES:0;
    }
    if(context->op!=BLE_GATT_ACCESS_OP_WRITE_CHR || channel>4) return BLE_ATT_ERR_WRITE_NOT_PERMITTED;
    const size_t count=OS_MBUF_PKTLEN(context->om); uint8_t bytes[512];
    if(count>sizeof(bytes) || os_mbuf_copydata(context->om,0,count,bytes)) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    if(channel==4) return self->artwork_.fragment(bytes,count)?0:BLE_ATT_ERR_UNLIKELY;
    auto& receiver=self->receivers_[channel];
    const auto result=receiver.accept(bytes,count);
    if(result==Protocol::Result::Invalid || result==Protocol::Result::BadOffset) {
        self->status(3,result==Protocol::Result::BadOffset?4:6,count>=4?Protocol::u16(bytes+2):0,receiver.offset());
        return BLE_ATT_ERR_UNLIKELY;
    }
    if(result==Protocol::Result::Complete) {
        if(receiver.completedNow()) {
            self->jsonBytes_[channel][receiver.total()]=0;
            self->jsonValid_[channel]=self->json(channel,self->jsonBytes_[channel].data(),receiver.total());
        }
        // Duplicate fragments acknowledge the same result without replaying
        // side effects (notably GPS age, clock synchronization and demo mode).
        if(!self->jsonValid_[channel]) {
            self->status(3,6,receiver.id(),0); return BLE_ATT_ERR_UNLIKELY;
        }
    }
    return 0;
}

bool BLEManager::json(unsigned channel,const uint8_t* data,size_t count) {
    if(!Protocol::validJsonText(data,count)) return false;
    const char* end=nullptr;
    std::unique_ptr<cJSON,decltype(&cJSON_Delete)> root(cJSON_ParseWithLengthOpts(reinterpret_cast<const char*>(data),count+1,&end,true),cJSON_Delete);
    if(!root || !cJSON_IsObject(root.get())) return false;
    const cJSON* r=root.get();
    if(channel==0) {
        std::string op; if(!textField(r,"op",op,32,false)) return false;
        double value=0,id=0,size=0,crc=0;
        if(op=="hello") { logLink(connectionHandle_.load(),"app ready"); status(1,0,0,1); return true; }
        if(op=="brightness") { if(!integer(r,"value",value,5,100)) return false; display_.setBrightness(value); return true; }
        if(op=="clock") {
            if(!integer(r,"unix",value,0,4102444800.0) || !integer(r,"offset_min",id,-840,840)) return false;
            display_.clock(value,id); return true;
        }
        if(op=="demo") { bool enabled=false; if(!boolean(r,"enabled",enabled)) return false; display_.demo(enabled); return true; }
        if(!integer(r,"id",id,1,65535)) return false;
        if(op=="art_begin") {
            if(!integer(r,"size",size,1,Protocol::ArtLimit) || !integer(r,"crc",crc,0,UINT32_MAX)) return false;
            artwork_.begin(id,size,crc); return true;
        }
        if(op=="art_commit") { artwork_.commit(id); return true; }
        if(op=="art_abort") { artwork_.abort(id); return true; }
        return false;
    }
    if(channel==2) {
        double speed,age,accuracy,limit,confidence,limitAge; std::string source;
        if(!number(r,"speed_mph",speed,0,300,true) || !integer(r,"gps_age_ms",age,0,UINT32_MAX,true) ||
           !number(r,"accuracy_m",accuracy,0,100000,true) || !integer(r,"limit_mph",limit,1,200,true) ||
           !number(r,"limit_confidence",confidence,0,1,true) || !integer(r,"limit_age_ms",limitAge,0,UINT32_MAX,true) ||
           !textField(r,"limit_source",source,128)) return false;
        display_.update([=](DisplayState& s) {
            s.gpsValid=speed>=0 && age>=0 && age<3000 && accuracy>=0 && accuracy<=50;
            s.speedMph=speed>=0?speed:0; s.gpsAgeMs=age>=0?static_cast<uint32_t>(age):UINT32_MAX; s.drivingReceivedMs=nowMs();
            s.speedLimitMph=limit>=1 && confidence>=0.8 && limitAge>=0 && limitAge<30000?limit:-1;
            s.limitConfidence=confidence>=0?confidence:0; s.limitAgeMs=limitAge>=0?static_cast<uint32_t>(limitAge):UINT32_MAX;
        }); return true;
    }
    if(channel==1) {
        bool active,reliable; std::string maneuver,distance,road,eta,source;
        if(!boolean(r,"active",active) || !boolean(r,"reliable",reliable) || !textField(r,"maneuver",maneuver,32) ||
           !textField(r,"distance",distance,96) || !textField(r,"road",road,96) || !textField(r,"eta",eta,32) ||
           !textField(r,"source",source,96)) return false;
        const std::array<std::string,10> known={"unknown","straight","left","right","slight_left","slight_right","uturn","roundabout","arrive",""};
        if(!reliable || std::find(known.begin(),known.end(),maneuver)==known.end()) maneuver="unknown";
        display_.update([=](DisplayState& s) {
            s.navigationActive=active; s.maneuver=maneuver; s.distance=distance; s.road=road; s.eta=eta;
            s.navigationStatus=reliable?"From phone navigation":"Instruction unavailable";
        }); return true;
    }
    if(channel==3) {
        std::string title,artist,album,track; bool playing; double duration,position,crc;
        if(!textField(r,"title",title,128) || !textField(r,"artist",artist,128) || !textField(r,"album",album,128) ||
           !textField(r,"track_id",track,128) || !boolean(r,"playing",playing) ||
           !integer(r,"duration_ms",duration,0,86400000,true) || !integer(r,"position_ms",position,0,86400000,true) ||
           !integer(r,"art_crc",crc,0,UINT32_MAX,true) || (duration>0 && position>duration)) return false;
        display_.update([=](DisplayState& s) {
            s.title=title.empty()?"No music":title; s.artist=artist; s.album=album; s.playing=playing;
            s.durationMs=duration>0?duration:0; s.positionMs=position>0?position:0; s.musicReceivedMs=nowMs(); s.artworkPresent=crc>=0; s.artworkId=crc>=0?crc:0;
        }); return true;
    }
    return false;
}
