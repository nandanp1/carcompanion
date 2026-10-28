#include "NavigationAudio.h"
#include "VoiceClipData.h"
#include "driver/i2s_std.h"
#include "esp_codec_dev_defaults.h"
#include "esp_log.h"
#include "freertos/task.h"
#include <algorithm>
#include <cstring>

extern const uint8_t voiceStart[] asm("_binary_navigation_pcm_start");
extern const uint8_t voiceEnd[] asm("_binary_navigation_pcm_end");

bool NavigationAudio::init(Board& board,uint8_t volume) {
    volume_=std::min<uint8_t>(volume,100);
    i2s_chan_handle_t tx=nullptr;
    i2s_chan_config_t channel=I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0,I2S_ROLE_MASTER);
    channel.dma_desc_num=4; channel.dma_frame_num=128; channel.auto_clear=true;
    if(i2s_new_channel(&channel,&tx,nullptr)!=ESP_OK) return false;
    i2s_std_config_t config={};
    config.clk_cfg=I2S_STD_CLK_DEFAULT_CONFIG(16000);
    config.slot_cfg=I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT,I2S_SLOT_MODE_STEREO);
    // Waveshare C6_AMOLED_2_16 board_cfg.txt, not the unrelated S3 board pin maps.
    config.gpio_cfg.mclk=GPIO_NUM_19; config.gpio_cfg.bclk=GPIO_NUM_20;
    config.gpio_cfg.ws=GPIO_NUM_22; config.gpio_cfg.dout=GPIO_NUM_23;
    config.gpio_cfg.din=I2S_GPIO_UNUSED;
    if(i2s_channel_init_std_mode(tx,&config)!=ESP_OK) return false;
    // Vendor initialization starts clocks before programming the slave codec.
    if(i2s_channel_enable(tx)!=ESP_OK) return false;
    audio_codec_i2s_cfg_t dataCfg={}; dataCfg.port=0; dataCfg.tx_handle=tx;
    const auto* data=audio_codec_new_i2s_data(&dataCfg);
    audio_codec_i2c_cfg_t ctrlCfg={}; ctrlCfg.port=0; ctrlCfg.addr=ES8311_CODEC_DEFAULT_ADDR;
    ctrlCfg.bus_handle=board.i2cBus();
    const auto* ctrl=audio_codec_new_i2c_ctrl(&ctrlCfg);
    const auto* gpio=audio_codec_new_gpio();
    if(!data || !ctrl || !gpio) return false;
    es8311_codec_cfg_t codecCfg={}; codecCfg.ctrl_if=ctrl; codecCfg.gpio_if=gpio;
    codecCfg.codec_mode=ESP_CODEC_DEV_WORK_MODE_DAC; codecCfg.pa_pin=-1;
    codecCfg.use_mclk=true; codecCfg.hw_gain.pa_gain=6;
    const auto* codec=es8311_codec_new(&codecCfg);
    if(!codec) return false;
    esp_codec_dev_cfg_t deviceCfg={}; deviceCfg.dev_type=ESP_CODEC_DEV_TYPE_OUT;
    deviceCfg.codec_if=codec; deviceCfg.data_if=data;
    codec_=esp_codec_dev_new(&deviceCfg);
    if(!codec_) return false;
    esp_codec_dev_sample_info_t format={}; format.sample_rate=16000; format.bits_per_sample=16; format.channel=2;
    if(esp_codec_dev_open(codec_,&format)!=ESP_CODEC_DEV_OK) return false;
    if(esp_codec_dev_set_out_vol(codec_,volume_)!=ESP_CODEC_DEV_OK) return false;
    queue_=xQueueCreate(1,sizeof(Voice::Prompt));
    if(!queue_) return false;
    if(xTaskCreate(run,"navigation_audio",6144,this,3,nullptr)!=pdPASS) return false;
    ready_=true;
    ESP_LOGI("NavAudio","ES8311 output ready; 16kHz stereo; volume=%u; bank=%u bytes",volume_.load(),unsigned(voiceEnd-voiceStart));
    return true;
}
void NavigationAudio::cancel() { ++generation_; if(queue_) xQueueReset(queue_); }
void NavigationAudio::setVolume(uint8_t value) {
    volume_=std::min<uint8_t>(value,100);
    if(!value) { cancel(); manualTest_=false; }
}
void NavigationAudio::enqueue(Voice::Prompt prompt) {
    if(!ready_ || !volume_) return;
    prompt.generation=++generation_;
    xQueueOverwrite(queue_,&prompt);
}
void NavigationAudio::test() {
    ESP_LOGI("NavAudio","Speaker test requested; ready=%u volume=%u",ready_,volume_.load());
    if(!ready_ || !volume_ || manualTest_.exchange(true)) return;
    enqueue({Voice::Clip::None,Voice::Clip::Test});
}
void NavigationAudio::update(const DisplayState& state,uint64_t now) {
    // A bench test must not be cancelled by an unknown/stale route snapshot.
    if(!ready_ || manualTest_) return;
    const bool usable=Voice::Policy::usable(state,now);
    const std::string key=state.maneuver+"\n"+state.road;
    if((wasUsable_ && !usable) || (usable && (key!=lastStepKey_ || state.navigationStep!=lastStep_))) cancel();
    if(usable && Voice::Policy::turn(state.maneuver)==Voice::Clip::None && state.road!="Rerouting…") cancel();
    wasUsable_=usable; lastStepKey_=key; lastStep_=state.navigationStep;
    if(auto prompt=policy_.update(state,now)) enqueue(*prompt);
}
bool NavigationAudio::play(Voice::Clip clip,uint32_t generation) {
    const auto index=static_cast<size_t>(clip);
    if(index>=sizeof(VoiceClips)/sizeof(VoiceClips[0])) return false;
    const auto range=VoiceClips[index];
    if(range.offset+range.size>static_cast<size_t>(voiceEnd-voiceStart)) return false;
    int16_t stereo[512];
    int appliedVolume=-1;
    for(size_t offset=0;offset<range.size;) {
        if(generation!=generation_.load() || !volume_) return false;
        const int volume=volume_;
        if(volume!=appliedVolume) {
            if(esp_codec_dev_set_out_vol(codec_,volume)!=ESP_CODEC_DEV_OK) return false;
            appliedVolume=volume;
        }
        const size_t samples=std::min<size_t>(256,(range.size-offset)/2);
        for(size_t i=0;i<samples;++i) {
            int16_t sample; memcpy(&sample,voiceStart+range.offset+offset+i*2,2);
            stereo[i*2]=stereo[i*2+1]=sample;
        }
        if(esp_codec_dev_write(codec_,stereo,samples*4)!=ESP_CODEC_DEV_OK) {
            ESP_LOGW("NavAudio","Speaker write failed"); return false;
        }
        offset+=samples*2;
    }
    return true;
}
void NavigationAudio::run(void* ptr) {
    auto* self=static_cast<NavigationAudio*>(ptr);
    Voice::Prompt prompt;
    for(;;) if(xQueueReceive(self->queue_,&prompt,portMAX_DELAY)==pdTRUE) {
        if(prompt.generation!=self->generation_.load() || !self->volume_) continue;
        self->speaking_=true;
        ESP_LOGI("NavAudio","Playback started; prompt=%u",unsigned(prompt.maneuver));
        bool complete=self->play(prompt.prefix,prompt.generation) && self->play(prompt.maneuver,prompt.generation);
        // Flush the short DMA tail with silence; never replay the final spoken samples at idle.
        int16_t silence[1024]={}; esp_codec_dev_write(self->codec_,silence,sizeof(silence));
        self->speaking_=false;
        if(prompt.maneuver==Voice::Clip::Test) self->manualTest_=false;
        ESP_LOGI("NavAudio","prompt=%u complete=%u volume=%u stack_min=%u",unsigned(prompt.maneuver),complete,self->volume_.load(),unsigned(uxTaskGetStackHighWaterMark(nullptr)));
    }
}
