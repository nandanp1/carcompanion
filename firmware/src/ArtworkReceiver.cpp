#include "ArtworkReceiver.h"
#include "rom/tjpgd.h"
#include "JpegBuffer.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
namespace {
UINT jpegRead(JDEC* decoder,BYTE* target,UINT requested) {
    return static_cast<JpegBuffer*>(decoder->device)->read(target,requested);
}
UINT jpegWrite(JDEC* decoder,void* rgb,JRECT* rect) {
    auto* buffer=static_cast<JpegBuffer*>(decoder->device);
    return buffer->writeRgb888(static_cast<const uint8_t*>(rgb),rect->left,rect->top,rect->right,rect->bottom)?1:0;
}
void* allocateRgb565(size_t bytes) { return heap_caps_malloc(bytes,MALLOC_CAP_8BIT); }
}
void ArtworkReceiver::init(DisplayManager& display,std::function<void(uint8_t,uint16_t,uint32_t)> status) {
    display_=&display; status_=status; mutex_=xSemaphoreCreateMutex(); assert(mutex_);
    const BaseType_t result=xTaskCreate(work,"jpeg_decode",6144,this,3,&task_); assert(result==pdPASS);
}
void ArtworkReceiver::begin(uint16_t id,uint32_t size,uint32_t crc) {
    xSemaphoreTake(mutex_,portMAX_DELAY);
    if(!id || !size || size>compressed_.size()) status_(INVALID,id,0);
    else if(busy_) status_(BUSY,id,receiver_.offset());
    else if(crc && cached_==crc) { receiver_.reset(); status_(CACHED,id,0); ESP_LOGI("Artwork","cached id=%u crc=%08lx",id,static_cast<unsigned long>(crc)); }
    else {
        if(receiver_.id()!=id || receiver_.total()!=size || expectedCrc_!=crc) {
            receiver_.expect(id,size); transferStartedUs_=esp_timer_get_time(); fragments_=0; progressAt_=0;
        }
        ESP_LOGI("Artwork","begin id=%u bytes=%lu offset=%lu crc=%08lx heap=%u largest=%u",id,
            static_cast<unsigned long>(size),static_cast<unsigned long>(receiver_.offset()),static_cast<unsigned long>(crc),
            heap_caps_get_free_size(MALLOC_CAP_8BIT),heap_caps_get_largest_free_block(MALLOC_CAP_8BIT));
        expectedCrc_=crc; status_(READY,id,receiver_.offset());
    }
    xSemaphoreGive(mutex_);
}
bool ArtworkReceiver::fragment(const uint8_t* bytes,size_t count) {
    xSemaphoreTake(mutex_,portMAX_DELAY);
    const uint16_t id=count>=4?Protocol::u16(bytes+2):0;
    auto result=busy_?Protocol::Result::Invalid:receiver_.accept(bytes,count);
    const bool ok=result==Protocol::Result::Accepted || result==Protocol::Result::Complete;
    if(ok) {
        ++fragments_;
        if(receiver_.offset()-progressAt_>=4096 || receiver_.completedNow()) {
            progressAt_=receiver_.offset();
            ESP_LOGI("Artwork","receive id=%u %lu/%lu bytes fragments=%lu elapsed=%llu ms",id,
                static_cast<unsigned long>(receiver_.offset()),static_cast<unsigned long>(receiver_.total()),static_cast<unsigned long>(fragments_),
                static_cast<unsigned long long>((esp_timer_get_time()-transferStartedUs_)/1000));
        }
    }
    if(!ok) status_(busy_?BUSY:result==Protocol::Result::BadOffset?BAD_OFFSET:INVALID,id,receiver_.offset());
    xSemaphoreGive(mutex_); return ok;
}
void ArtworkReceiver::commit(uint16_t id) {
    xSemaphoreTake(mutex_,portMAX_DELAY);
    if(busy_) status_(BUSY,id,receiver_.offset());
    else if(!id || id!=receiver_.id()) status_(INVALID,id,0);
    else if(receiver_.offset()!=receiver_.total()) status_(BAD_OFFSET,id,receiver_.offset());
    else {
        ESP_LOGI("Artwork","commit id=%u bytes=%lu transfer=%llu ms",id,static_cast<unsigned long>(receiver_.total()),
            static_cast<unsigned long long>((esp_timer_get_time()-transferStartedUs_)/1000));
        busy_=true; xTaskNotifyGive(task_);
    }
    xSemaphoreGive(mutex_);
}
void ArtworkReceiver::abort(uint16_t id) {
    xSemaphoreTake(mutex_,portMAX_DELAY); if(!busy_ && receiver_.id()==id) receiver_.reset(); xSemaphoreGive(mutex_);
}
void ArtworkReceiver::disconnected() {
    xSemaphoreTake(mutex_,portMAX_DELAY);
    if(receiver_.id()) ESP_LOGI("Artwork","disconnect id=%u offset=%lu decode_busy=%u",receiver_.id(),static_cast<unsigned long>(receiver_.offset()),busy_);
    if(!busy_) receiver_.reset();
    xSemaphoreGive(mutex_);
}
void ArtworkReceiver::work(void* self) {
    for(;;) { ulTaskNotifyTake(pdTRUE,portMAX_DELAY); static_cast<ArtworkReceiver*>(self)->decode(); }
}
void ArtworkReceiver::decode() {
    // While busy_ is true the BLE host cannot modify the compressed buffer.
    const uint16_t id=receiver_.id(); const uint32_t size=receiver_.total();
    const uint64_t transferStarted=transferStartedUs_;
    const uint64_t started=esp_timer_get_time();
    Status result=BAD_CRC;
    const uint32_t actualCrc=Protocol::crc32(compressed_.data(),size);
    if(actualCrc==expectedCrc_) {
        // ESP32-C6 ROM TJpgDec: the same decoder selected by esp_jpeg, with
        // bounded callbacks for both input reads AND skips, including malformed JPEGs.
        JpegBuffer buffer; buffer.input=compressed_.data(); buffer.size=size;
        alignas(4) uint8_t scratch[3100]; JDEC decoder={};
        result=DECODE_FAILED;
        const JRESULT prepared=jd_prepare(&decoder,jpegRead,scratch,sizeof(scratch),&buffer);
        ESP_LOGI("Artwork","prepare id=%u result=%d image=%ux%u input=%u/%lu bytes",id,prepared,decoder.width,decoder.height,
            buffer.offset,static_cast<unsigned long>(size));
        if(prepared==JDR_OK && decoder.width && decoder.height && decoder.width<=240 && decoder.height<=240) {
            // C6 has no PSRAM. Transfer the sole output allocation from LVGL
            // to the decoder while no LVGL reader can retain a reference.
            // Reusing it prevents fragmentation from shrinking the next cover.
            cached_=0; auto output=display_->detachArtwork();
            unsigned scale=0;
            buffer.width=decoder.width; buffer.height=decoder.height;
            ESP_LOGI("Artwork","RGB565 requested=%u reusable=%u heap=%u largest=%u minimum=%u",buffer.width*buffer.height*2,output.capacity(),
                heap_caps_get_free_size(MALLOC_CAP_8BIT),heap_caps_get_largest_free_block(MALLOC_CAP_8BIT),esp_get_minimum_free_heap_size());
            bool allocated=output.ensure(buffer.width*buffer.height*2,allocateRgb565);
            // A connected C6 can have a fragmented internal heap. ROM TJpgDec
            // supports true 1/2 scaling, avoiding a second intermediate image.
            if(!allocated && buffer.width>=2 && buffer.height>=2) {
                ESP_LOGW("Artwork","full RGB565 allocation unavailable; trying half-scale");
                scale=1; buffer.width>>=1; buffer.height>>=1;
                allocated=output.ensure(buffer.width*buffer.height*2,allocateRgb565);
            }
            if(allocated) {
                buffer.output=reinterpret_cast<uint16_t*>(output.data());
                const JRESULT decoded=jd_decomp(&decoder,jpegWrite,scale);
                ESP_LOGI("Artwork","decode id=%u result=%d scale=%u input=%u/%lu bytes elapsed=%llu ms",id,decoded,scale,
                    buffer.offset,static_cast<unsigned long>(size),static_cast<unsigned long long>((esp_timer_get_time()-started)/1000));
                if(decoded==JDR_OK) {
                    display_->artwork(std::move(output),buffer.width,buffer.height,expectedCrc_);
                    cached_=expectedCrc_; result=COMPLETE;
                    ESP_LOGI("Artwork","decoded %u x %u RGB565",buffer.width,buffer.height);
                }
            } else ESP_LOGE("Artwork","RGB565 allocation failed even at half-scale; largest=%u",heap_caps_get_largest_free_block(MALLOC_CAP_8BIT));
        }
    } else ESP_LOGW("Artwork","CRC mismatch id=%u expected=%08lx actual=%08lx",id,static_cast<unsigned long>(expectedCrc_),static_cast<unsigned long>(actualCrc));
    xSemaphoreTake(mutex_,portMAX_DELAY);
    receiver_.reset(); busy_=false; status_(result,id,result==COMPLETE?size:0);
    xSemaphoreGive(mutex_);
    ESP_LOGI("Artwork","done id=%u status=%u decoder=%llu ms total=%llu ms heap=%u largest=%u stack_min=%u",id,result,
        static_cast<unsigned long long>((esp_timer_get_time()-started)/1000),static_cast<unsigned long long>((esp_timer_get_time()-transferStarted)/1000),
        esp_get_free_heap_size(),heap_caps_get_largest_free_block(MALLOC_CAP_8BIT),uxTaskGetStackHighWaterMark(nullptr));
}
