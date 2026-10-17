#pragma once
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <utility>

// Sole owner of the decoded RGB565 allocation. Move it between the display
// and decoder only after LVGL's image reference has been detached under its
// mutex. A failed decode automatically releases its allocation.
class ArtworkBuffer {
public:
    static constexpr size_t MaximumBytes=240*240*2;
    ArtworkBuffer()=default;
    ~ArtworkBuffer() { reset(); }
    ArtworkBuffer(const ArtworkBuffer&)=delete;
    ArtworkBuffer& operator=(const ArtworkBuffer&)=delete;
    ArtworkBuffer(ArtworkBuffer&& other) noexcept:
        pixels_(std::exchange(other.pixels_,nullptr)),capacity_(std::exchange(other.capacity_,0)) {}
    ArtworkBuffer& operator=(ArtworkBuffer&& other) noexcept {
        if(this!=&other) {
            reset(); pixels_=std::exchange(other.pixels_,nullptr); capacity_=std::exchange(other.capacity_,0);
        }
        return *this;
    }
    bool ensure(size_t bytes,void* (*allocate)(size_t)=std::malloc) {
        if(!bytes || bytes>MaximumBytes) return false;
        if(pixels_ && capacity_>=bytes) return true;
        // Never retain two decoded allocations while trying to grow one.
        reset(); pixels_=static_cast<uint8_t*>(allocate(bytes));
        capacity_=pixels_?bytes:0;
        return pixels_!=nullptr;
    }
    uint8_t* data() const { return pixels_; }
    size_t capacity() const { return capacity_; }
private:
    void reset() { std::free(pixels_); pixels_=nullptr; capacity_=0; }
    uint8_t* pixels_=nullptr;
    size_t capacity_=0;
};
