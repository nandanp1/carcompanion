#include "Protocol.h"
#include <cstring>
namespace Protocol {
uint16_t u16(const uint8_t* p) { return p[0]|(uint16_t(p[1])<<8); }
uint32_t u32(const uint8_t* p) { return u16(p)|(uint32_t(u16(p+2))<<16); }
void put16(uint8_t* p,uint16_t v) { p[0]=v; p[1]=v>>8; }
void put32(uint8_t* p,uint32_t v) { put16(p,v); put16(p+2,v>>16); }
uint32_t crc32(const uint8_t* data,size_t count) {
    uint32_t crc=0xffffffffu;
    for(size_t n=0;n<count;n++) { crc^=data[n]; for(int k=0;k<8;k++) crc=(crc>>1)^(0xedb88320u & (0u-(crc&1u))); }
    return crc^0xffffffffu;
}
static bool utf8(const uint8_t* data,size_t size) {
    for(size_t i=0;i<size;) {
        const uint8_t lead=data[i++]; if(!lead) return false; if(lead<0x80) continue;
        unsigned need=0; uint32_t code=0,min=0;
        if((lead&0xe0)==0xc0) { need=1; code=lead&31; min=0x80; }
        else if((lead&0xf0)==0xe0) { need=2; code=lead&15; min=0x800; }
        else if((lead&0xf8)==0xf0) { need=3; code=lead&7; min=0x10000; }
        else return false;
        if(i+need>size) return false;
        while(need--) { const uint8_t next=data[i++]; if((next&0xc0)!=0x80) return false; code=(code<<6)|(next&63); }
        if(code<min || code>0x10ffff || (code>=0xd800 && code<=0xdfff)) return false;
    }
    return true;
}
static bool boundedJsonNesting(const uint8_t* data,size_t size) {
    unsigned depth=0; bool quoted=false,escape=false;
    for(size_t i=0;i<size;i++) {
        const char c=data[i];
        if(quoted) {
            if(escape) escape=false;
            else if(c=='\\') escape=true;
            else if(c=='"') quoted=false;
        } else if(c=='"') quoted=true;
        else if(c=='{' || c=='[') { if(++depth>8) return false; }
        else if(c=='}' || c==']') { if(!depth) return false; --depth; }
    }
    return !quoted && !depth;
}
bool validJsonText(const uint8_t* data,size_t size) { return utf8(data,size) && boundedJsonNesting(data,size); }
Result Receiver::accept(const uint8_t* frame,size_t size) {
    completedNow_=false;
    if(size<=HeaderSize || size>512 || frame[0]!=Version || (frame[1]&~3)) return Result::Invalid;
    const uint16_t id=u16(frame+2);
    const uint32_t offset=u32(frame+4),total=u32(frame+8);
    const size_t count=size-HeaderSize;
    if(!id || !total || total>capacity_ || offset>total || count>total-offset) return Result::Invalid;
    if(bool(frame[1]&1)!=(offset==0) || bool(frame[1]&2)!=(count==total-offset)) return Result::Invalid;
    if((frame[1]&1) && replace_ && (id!=id_ || total!=total_)) expect(id,total);
    if(id!=id_ || total!=total_) return Result::Invalid;
    if(offset>offset_) return Result::BadOffset;
    if(offset<offset_) {
        if(count>offset_-offset || memcmp(data_+offset,frame+HeaderSize,count)) return Result::BadOffset;
        return offset_==total_?Result::Complete:Result::Accepted;
    }
    memcpy(data_+offset,frame+HeaderSize,count); offset_+=count; completedNow_=offset_==total_;
    return offset_==total_?Result::Complete:Result::Accepted;
}
}
