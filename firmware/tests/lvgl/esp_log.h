#pragma once
#include <cstdio>
#define ESP_LOGI(tag,format,...) do { std::printf("[%s] " format "\n",tag,##__VA_ARGS__); } while(false)
