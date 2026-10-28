#include "Board.h"
#include "driver/spi_master.h"
#include "driver/gpio.h"
#include "esp_lcd_sh8601.h"
#include "esp_lcd_touch_cst9217.h"
#include "XPowersLib.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include <cstring>
#include <algorithm>

namespace {
i2c_master_dev_handle_t pmic=nullptr;
XPowersPMU power;
int pmicRead(uint8_t, uint8_t reg, uint8_t* out,uint8_t size) {
    return i2c_master_transmit_receive(pmic,&reg,1,out,size,200)==ESP_OK?0:-1;
}
int pmicWrite(uint8_t,uint8_t reg,uint8_t* data,uint8_t size) {
    uint8_t message[256]; message[0]=reg; memcpy(message+1,data,size);
    return i2c_master_transmit(pmic,message,size+1,200)==ESP_OK?0:-1;
}
// CO5300 register values are the hardware initialization data published in
// Waveshare commit 294543798f1a44e2f2c4d2976522323f2beee11d, display_bsp.cpp.
const uint8_t zero[]={0}, page20[]={0x20}, ten[]={0x10}, a0[]={0xa0}, eighty[]={0x80};
const uint8_t rgb565[]={0x55}, rotation[]={0x30}, full[]={0xff}, range[]={0,0,1,0xdf};
const sh8601_lcd_init_cmd_t initCommands[]={
    {0x11,zero,0,600},{0xfe,page20,1,0},{0x19,ten,1,0},{0x1c,a0,1,0},
    {0xfe,zero,1,0},{0xc4,eighty,1,0},{0x3a,rgb565,1,0},{0x35,zero,1,0},
    {0x36,rotation,1,0},{0x53,page20,1,0},{0x51,full,1,0},{0x63,full,1,0},
    {0x2a,range,4,0},{0x2b,range,4,0},{0x29,zero,0,100}
};
}
void Board::init() {
    i2c_master_bus_config_t cfg={}; cfg.i2c_port=I2C_NUM_0;
    cfg.scl_io_num=static_cast<gpio_num_t>(BoardPins::Scl); cfg.sda_io_num=static_cast<gpio_num_t>(BoardPins::Sda);
    cfg.clk_source=I2C_CLK_SRC_DEFAULT; cfg.glitch_ignore_cnt=7; cfg.flags.enable_internal_pullup=true;
    ESP_ERROR_CHECK(i2c_new_master_bus(&cfg,&bus));
    i2c_device_config_t p={}; p.dev_addr_length=I2C_ADDR_BIT_LEN_7; p.device_address=0x34; p.scl_speed_hz=100000;
    ESP_ERROR_CHECK(i2c_master_bus_add_device(bus,&p,&pmic));
    ESP_ERROR_CHECK(power.begin(0x34,pmicRead,pmicWrite)?ESP_OK:ESP_FAIL);
    // Side keys: BOOT=GPIO9, KEY/IO10=GPIO10. PWR is AXP2101 PWRON.
    gpio_config_t buttons={}; buttons.pin_bit_mask=(1ULL<<9)|(1ULL<<10);
    buttons.mode=GPIO_MODE_INPUT; buttons.pull_up_en=GPIO_PULLUP_ENABLE;
    ESP_ERROR_CHECK(gpio_config(&buttons));
    power.enableIRQ(XPOWERS_AXP2101_PKEY_SHORT_IRQ);
    uint8_t clearShort=0x08; pmicWrite(0x34,0x49,&clearShort,1);
    // Factory example voltage rails. Keep charger configuration at factory
    // settings: this project neither needs nor assumes an attached battery.
    power.setDC1Voltage(3300); power.setALDO1Voltage(3300); power.setALDO2Voltage(3300);
    power.setALDO3Voltage(3300); power.setALDO4Voltage(3300);

    spi_bus_config_t spi={}; spi.sclk_io_num=BoardPins::Clock;
    spi.data0_io_num=BoardPins::Data0; spi.data1_io_num=BoardPins::Data1;
    spi.data2_io_num=BoardPins::Data2; spi.data3_io_num=BoardPins::Data3;
    spi.max_transfer_sz=480*20*2;
    ESP_ERROR_CHECK(spi_bus_initialize(SPI2_HOST,&spi,SPI_DMA_CH_AUTO));
    esp_lcd_panel_io_spi_config_t lcd={}; lcd.cs_gpio_num=BoardPins::DisplayCs; lcd.dc_gpio_num=-1;
    lcd.pclk_hz=40000000; lcd.trans_queue_depth=1; lcd.lcd_cmd_bits=32; lcd.lcd_param_bits=8;
    lcd.flags.quad_mode=true;
    ESP_ERROR_CHECK(esp_lcd_new_panel_io_spi(static_cast<esp_lcd_spi_bus_handle_t>(SPI2_HOST),&lcd,&io));
    sh8601_vendor_config_t vendor={}; vendor.init_cmds=initCommands;
    vendor.init_cmds_size=sizeof(initCommands)/sizeof(initCommands[0]); vendor.flags.use_qspi_interface=1;
    esp_lcd_panel_dev_config_t panelCfg={}; panelCfg.reset_gpio_num=-1;
    panelCfg.rgb_ele_order=LCD_RGB_ELEMENT_ORDER_RGB; panelCfg.bits_per_pixel=16; panelCfg.vendor_config=&vendor;
    ESP_ERROR_CHECK(esp_lcd_new_panel_sh8601(io,&panelCfg,&panel));
    // The display reset is powered by PMIC ALDO3, not an ESP GPIO.
    power.enableALDO3(); vTaskDelay(pdMS_TO_TICKS(100));
    power.disableALDO3(); vTaskDelay(pdMS_TO_TICKS(100));
    power.enableALDO3(); vTaskDelay(pdMS_TO_TICKS(100));
    ESP_ERROR_CHECK(esp_lcd_panel_init(panel));

    // Exact ESP_LCD_TOUCH_IO_I2C_CST9217_CONFIG values from the pinned
    // Waveshare driver, with all new IDF fields explicitly zero-initialized.
    esp_lcd_panel_io_i2c_config_t tc={}; tc.dev_addr=ESP_LCD_TOUCH_IO_I2C_CST9217_ADDRESS;
    tc.control_phase_bytes=1; tc.dc_bit_offset=0; tc.lcd_cmd_bits=8;
    tc.flags.disable_control_phase=1; tc.scl_speed_hz=400000;
    esp_lcd_panel_io_handle_t ti=nullptr;
    ESP_ERROR_CHECK(esp_lcd_new_panel_io_i2c(bus,&tc,&ti));
    esp_lcd_touch_config_t touchCfg={}; touchCfg.x_max=480; touchCfg.y_max=480;
    touchCfg.rst_gpio_num=static_cast<gpio_num_t>(BoardPins::TouchReset);
    touchCfg.int_gpio_num=static_cast<gpio_num_t>(BoardPins::TouchIrq);
    touchCfg.flags.swap_xy=1; touchCfg.flags.mirror_y=1;
    ESP_ERROR_CHECK(esp_lcd_touch_new_i2c_cst9217(ti,&touchCfg,&touch));
    ESP_LOGI("Board","CO5300 480x480 / CST9220 initialized; no PSRAM");
}
void Board::brightness(uint8_t percent) {
    const uint8_t value=static_cast<uint8_t>(std::min<uint16_t>(percent,100)*255/100);
    ESP_ERROR_CHECK(esp_lcd_panel_io_tx_param(io,0x02005100,&value,1));
}

bool Board::volumeDownPressed() const { return gpio_get_level(GPIO_NUM_10)==0; }
bool Board::volumeUpPressed() const { return gpio_get_level(GPIO_NUM_9)==0; }
bool Board::powerShortPress() {
    // Read/clear only the PKEY_SHORT W1C bit. Failed I2C reads are never button presses.
    uint8_t irq=0;
    if(pmicRead(0x34,0x49,&irq,1)!=0 || !(irq&0x08)) return false;
    uint8_t clear=0x08;
    return pmicWrite(0x34,0x49,&clear,1)==0;
}
