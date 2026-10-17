#pragma once
#include <cstddef>
#include <cstdint>
#include <array>
namespace Protocol {
constexpr uint8_t Version=1;
constexpr size_t HeaderSize=12, JsonLimit=1024, ArtLimit=32768;
enum class Result : uint8_t { Accepted, Complete, BadOffset, Invalid };
uint16_t u16(const uint8_t* p);
uint32_t u32(const uint8_t* p);
void put16(uint8_t* p,uint16_t v);
void put32(uint8_t* p,uint32_t v);
uint32_t crc32(const uint8_t* data,size_t count);
// UTF-8 and nesting preflight; cJSON still performs full syntax/type validation.
bool validJsonText(const uint8_t* data,size_t size);
// Allocation-free framing, also compiled by the host tests.
class Receiver {
public:
    Receiver(uint8_t* bytes,size_t capacity,bool replaceOnStart=true): data_(bytes), capacity_(capacity), replace_(replaceOnStart) {}
    Result accept(const uint8_t* frame,size_t size);
    void reset() { id_=0; total_=offset_=0; completedNow_=false; }
    void expect(uint16_t id,uint32_t size) { id_=id; total_=size; offset_=0; completedNow_=false; }
    uint16_t id() const { return id_; }
    uint32_t offset() const { return offset_; }
    uint32_t total() const { return total_; }
    bool completedNow() const { return completedNow_; }
private:
    uint8_t* data_;
    size_t capacity_;
    bool replace_;
    uint16_t id_=0;
    uint32_t total_=0,offset_=0;
    bool completedNow_=false;
};
}
