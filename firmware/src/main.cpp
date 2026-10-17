#include "BLEManager.h"
#include "sdkconfig.h"
#include "esp_log.h"
static Board board;
static SettingsManager settings;
static DisplayManager display;
static BLEManager bluetooth(display);
extern "C" void app_main() {
    settings.init(); board.init();
    display.init(board,settings,[](uint8_t command) { bluetooth.playback(command); });
    bluetooth.init();
#ifdef CONFIG_COMPANION_SIMULATED_DATA
    display.demo(true);
#endif
    ESP_LOGI("CarCompanion","Firmware 0.1.0 / protocol 1.0 / build 1 ready");
}
