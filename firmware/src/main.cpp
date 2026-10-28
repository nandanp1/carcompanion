#include "BLEManager.h"
#include "sdkconfig.h"
#include "esp_log.h"
static Board board;
static SettingsManager settings;
static NavigationAudio audio;
static DisplayManager display;
static BLEManager bluetooth(display);
extern "C" void app_main() {
    settings.init(); board.init();
    if(!audio.init(board,settings.volume())) ESP_LOGE("NavAudio","Speaker unavailable; visual navigation remains active");
    display.init(board,settings,audio,[](uint8_t command) { bluetooth.playback(command); });
    bluetooth.init();
#ifdef CONFIG_COMPANION_SIMULATED_DATA
    display.demo(true);
#endif
    ESP_LOGI("CarCompanion","Firmware 0.3.0 / protocol 1.0 / build 3 ready");
}
