#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <algorithm>

// Bounds enforcement shared by the ROM decoder callbacks and host tests.
struct JpegBuffer {
    const uint8_t* input=nullptr;
    size_t size=0,offset=0;
    uint16_t* output=nullptr;
    uint16_t width=0,height=0;
    size_t read(uint8_t* target,size_t requested) {
        if(offset>size) return 0;
        const size_t count=std::min(requested,size-offset);
        if(target && count) memcpy(target,input+offset,count);
        offset+=count; return count;
    }
    bool writeRgb888(const uint8_t* rgb,unsigned left,unsigned top,unsigned right,unsigned bottom) {
        if(!output || !rgb || right<left || bottom<top || right>=width || bottom>=height) return false;
        for(unsigned y=top;y<=bottom;y++) for(unsigned x=left;x<=right;x++) {
            output[y*width+x]=uint16_t((rgb[0]&0xf8)<<8)|uint16_t((rgb[1]&0xfc)<<3)|(rgb[2]>>3);
            rgb+=3;
        }
        return true;
    }
};
