idf_component_register(SRCS "include/XPowersLibInterface.cpp" INCLUDE_DIRS "include" REQUIRES driver)
target_compile_definitions(${COMPONENT_LIB} PUBLIC XPOWERS_CHIP_AXP2101 CONFIG_XPOWERS_ESP_IDF_NEW_API)
