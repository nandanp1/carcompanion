#include "ArtworkReceiver.h"
#include "rom/tjpgd.h"
#include "JpegBuffer.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_system.h"
namespace {
UINT jpegRead(JDEC* decoder,BYTE* target,UINT requested) {
    return static_cast<JpegBuffer*>(decoder->device)->read(target,requested);
}
UINT jpegWrite(JDEC* decoder,void* rgb,JRECT* rect) {
    auto* buffer=static_cast<JpegBuffer*>(decoder->device);
    return buffer->writeRgb888(static_cast<const uint8_t*>(rgb),rect->left,rect->top,rect->right,rect->bottom)?1:0;
}
}
void ArtworkReceiver::init(DisplayManager& display,std::function<void(uint8_t,uint16_t,uint32_t)> status) {
    display_=&display; status_=status; mutex_=xSemaphoreCreateMutex(); assert(mutex_);
    const BaseType_t result=xTaskCreate(work,"jpeg_decode",6144,this,3,&task_); assert(result==pdPASS);
}
void ArtworkReceiver::begin(uint16_t id,uint32_t size,uint32_t crc) {
    xSemaphoreTake(mutex_,portMAX_DELAY);
    if(!id || !size || size>compressed_.size()) status_(INVALID,id,0);
    else if(busy_) status_(BUSY,id,receiver_.offset());
    else if(crc && cached_==crc) { receiver_.reset(); status_(CACHED,id,0); }
    else {
        if(receiver_.id()!=id || receiver_.total()!=size || expectedCrc_!=crc) receiver_.expect(id,size);
        expectedCrc_=crc; status_(READY,id,receiver_.offset());
    }
    xSemaphoreGive(mutex_);
}
bool ArtworkReceiver::fragment(const uint8_t* bytes,size_t count) {
    xSemaphoreTake(mutex_,portMAX_DELAY);
    const uint16_t id=count>=4?Protocol::u16(bytes+2):0;
    auto result=busy_?Protocol::Result::Invalid:receiver_.accept(bytes,count);
    const bool ok=result==Protocol::Result::Accepted || result==Protocol::Result::Complete;
    if(!ok) status_(busy_?BUSY:result==Protocol::Result::BadOffset?BAD_OFFSET:INVALID,id,receiver_.offset());
    xSemaphoreGive(mutex_); return ok;
}
void ArtworkReceiver::commit(uint16_t id) {
    xSemaphoreTake(mutex_,portMAX_DELAY);
    if(busy_) status_(BUSY,id,receiver_.offset());
    else if(!id || id!=receiver_.id()) status_(INVALID,id,0);
    else if(receiver_.offset()!=receiver_.total()) status_(BAD_OFFSET,id,receiver_.offset());
    else { busy_=true; xTaskNotifyGive(task_); }
    xSemaphoreGive(mutex_);
}
void ArtworkReceiver::abort(uint16_t id) {
    xSemaphoreTake(mutex_,portMAX_DELAY); if(!busy_ && receiver_.id()==id) receiver_.reset(); xSemaphoreGive(mutex_);
}
void ArtworkReceiver::disconnected() {
    xSemaphoreTake(mutex_,portMAX_DELAY); if(!busy_) receiver_.reset(); xSemaphoreGive(mutex_);
}
void ArtworkReceiver::work(void* self) {
    for(;;) { ulTaskNotifyTake(pdTRUE,portMAX_DELAY); static_cast<ArtworkReceiver*>(self)->decode(); }
}
void ArtworkReceiver::decode() {
    // While busy_ is true the BLE host cannot modify the compressed buffer.
    const uint16_t id=receiver_.id(); const uint32_t size=receiver_.total();
    Status result=BAD_CRC;
    if(Protocol::crc32(compressed_.data(),size)==expectedCrc_) {
        // ESP32-C6 ROM TJpgDec: the same decoder selected by esp_jpeg, with
        // bounded callbacks for both input reads AND skips, including malformed JPEGs.
        JpegBuffer buffer; buffer.input=compressed_.data(); buffer.size=size;
        alignas(4) uint8_t scratch[3100]; JDEC decoder={};
        result=DECODE_FAILED;
        if(jd_prepare(&decoder,jpegRead,scratch,sizeof(scratch),&buffer)==JDR_OK && decoder.width && decoder.height && decoder.width<=240 && decoder.height<=240) {
            // C6 has no PSRAM. Keep at most ONE output image: detach under the
            // LVGL mutex before freeing, decode without holding that mutex.
            cached_=0; display_->detachArtwork();
            unsigned scale=0;
            buffer.width=decoder.width; buffer.height=decoder.height;
            auto* output=static_cast<uint8_t*>(heap_caps_malloc(buffer.width*buffer.height*2,MALLOC_CAP_8BIT));
            // A connected C6 can have a fragmented internal heap. ROM TJpgDec
            // supports true 1/2 scaling, avoiding a second intermediate image.
            if(!output && buffer.width>=2 && buffer.height>=2) {
                scale=1; buffer.width>>=1; buffer.height>>=1;
                output=static_cast<uint8_t*>(heap_caps_malloc(buffer.width*buffer.height*2,MALLOC_CAP_8BIT));
            }
            if(output) {
                buffer.output=reinterpret_cast<uint16_t*>(output);
                if(jd_decomp(&decoder,jpegWrite,scale)==JDR_OK) {
                    display_->artwork(output,buffer.width,buffer.height,expectedCrc_);
                    cached_=expectedCrc_; result=COMPLETE;
                    ESP_LOGI("Artwork","decoded %u x %u RGB565",buffer.width,buffer.height);
                } else free(output);
            }
        }
    }
    xSemaphoreTake(mutex_,portMAX_DELAY);
    receiver_.reset(); busy_=false; status_(result,id,result==COMPLETE?size:0);
    xSemaphoreGive(mutex_);
    ESP_LOGI("Artwork","transfer %u status %u, free heap %lu",id,result,static_cast<unsigned long>(esp_get_free_heap_size()));
}
