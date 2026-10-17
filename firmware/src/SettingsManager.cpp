#include "SettingsManager.h"
#include "nvs_flash.h"
#include "esp_err.h"
void SettingsManager::init() {
    esp_err_t result=nvs_flash_init();
    if(result==ESP_ERR_NVS_NO_FREE_PAGES || result==ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase()); result=nvs_flash_init();
    }
    ESP_ERROR_CHECK(result);
    ESP_ERROR_CHECK(nvs_open("companion",NVS_READWRITE,&handle_));
    uint8_t page=1;
    nvs_get_u8(handle_,"brightness",&brightness_);
    nvs_get_u8(handle_,"page",&page);
    brightness_=std::clamp<uint8_t>(brightness_,5,100);
    page_=page<=2?static_cast<Page>(page):Page::Driving;
}
void SettingsManager::setBrightness(uint8_t value) {
    value=std::clamp<uint8_t>(value,5,100);
    if(brightness_==value) return;
    brightness_=value; nvs_set_u8(handle_,"brightness",value); nvs_commit(handle_);
}
void SettingsManager::setPage(Page value) {
    if(page_==value) return;
    page_=value; nvs_set_u8(handle_,"page",static_cast<uint8_t>(value)); nvs_commit(handle_);
}
