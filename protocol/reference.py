"""Executable BLE 1.0 conformance oracle; no third-party packages required.

Production endpoints are Kotlin WireProtocol and C++ Protocol. This independent
implementation generates shared vectors and tests wire edge cases on any host.
"""
from __future__ import annotations

from dataclasses import dataclass
import struct
import zlib

HEADER = struct.Struct("<BBHII")
STATUS = struct.Struct("<BBBBHHII")
MAX_JSON = 1024
MAX_ARTWORK = 32768


class ProtocolError(ValueError):
    pass


@dataclass(frozen=True)
class Frame:
    flags: int
    transfer_id: int
    offset: int
    total: int
    payload: bytes

    def encode(self) -> bytes:
        raw = HEADER.pack(1, self.flags, self.transfer_id, self.offset, self.total) + self.payload
        Frame.decode(raw)
        return raw

    @classmethod
    def decode(cls, raw: bytes, maximum: int = MAX_ARTWORK) -> "Frame":
        if not HEADER.size < len(raw) <= 512:
            raise ProtocolError("invalid ATT value length")
        version, flags, ident, offset, total = HEADER.unpack_from(raw)
        payload = raw[HEADER.size:]
        end = offset + len(payload)
        if version != 1 or flags & ~3 or ident == 0:
            raise ProtocolError("unsupported version, flags or ID")
        if not 0 < total <= maximum or offset >= total or end > total:
            raise ProtocolError("out-of-bounds fragment")
        if bool(flags & 1) != (offset == 0) or bool(flags & 2) != (end == total):
            raise ProtocolError("inconsistent START/END")
        return cls(flags, ident, offset, total, payload)


def fragment(payload: bytes, transfer_id: int, mtu: int = 23, offset: int = 0):
    if not 23 <= mtu <= 517 or not 1 <= transfer_id <= 65535:
        raise ProtocolError("invalid MTU or transfer ID")
    if not payload or len(payload) > MAX_ARTWORK or not 0 <= offset <= len(payload):
        raise ProtocolError("invalid payload or offset")
    capacity = min(mtu - 3, 512) - HEADER.size
    for start in range(offset, len(payload), capacity):
        data = payload[start:start + capacity]
        flags = int(start == 0) | (2 if start + len(data) == len(payload) else 0)
        yield Frame(flags, transfer_id, start, len(payload), data).encode()


class Receiver:
    """Bounded, ordered receiver with exact duplicate detection."""
    def __init__(self, maximum: int = MAX_JSON):
        self.maximum = maximum
        self.reset()

    def reset(self):
        self.transfer_id = 0
        self.total = 0
        self.data = bytearray()

    def accept(self, raw: bytes) -> bool:
        frame = Frame.decode(raw, self.maximum)
        if frame.offset == 0 and (frame.transfer_id != self.transfer_id or frame.total != self.total):
            self.reset()
            self.transfer_id, self.total = frame.transfer_id, frame.total
        if (frame.transfer_id, frame.total) != (self.transfer_id, self.total):
            raise ProtocolError("ID/total changed without START")
        if frame.offset < len(self.data):
            if frame.offset + len(frame.payload) > len(self.data):
                raise ProtocolError("overlapping fragment")
            if self.data[frame.offset:frame.offset + len(frame.payload)] != frame.payload:
                raise ProtocolError("inconsistent duplicate")
        elif frame.offset != len(self.data):
            raise ProtocolError("gap")
        else:
            self.data.extend(frame.payload)
        return len(self.data) == self.total


class ArtworkReceiver:
    """Transaction oracle. Decoder callback models the firmware decoder boundary."""
    def __init__(self):
        self.cached_crc = 0
        self.cached_data = b""
        self.active = None
        self.expected_crc = 0

    def begin(self, transfer_id: int, size: int, crc: int):
        if not 1 <= transfer_id <= 65535 or not 0 < size <= MAX_ARTWORK or not 0 <= crc <= 0xFFFFFFFF:
            raise ProtocolError("invalid artwork begin")
        if crc and self.cached_crc == crc:
            return "cached", size
        if self.active and (self.active.transfer_id, self.active.total, self.expected_crc) == (transfer_id, size, crc):
            return "ready", len(self.active.data)
        self.active = Receiver(MAX_ARTWORK)
        self.active.transfer_id, self.active.total = transfer_id, size
        self.expected_crc = crc
        return "ready", 0

    def accept(self, raw: bytes):
        frame = Frame.decode(raw)
        if self.active is None or (frame.transfer_id, frame.total) != (self.active.transfer_id, self.active.total):
            raise ProtocolError("artwork frame has no matching begin")
        return self.active.accept(raw)

    def commit(self, transfer_id: int, decode=lambda data: True):
        if self.active is None or transfer_id != self.active.transfer_id:
            raise ProtocolError("unknown artwork transaction")
        if len(self.active.data) != self.active.total:
            return "bad_offset"
        data = bytes(self.active.data)
        expected = self.expected_crc
        self.active = None
        if zlib.crc32(data) != expected:
            return "bad_crc"
        if not decode(data):
            return "decode_failed"
        self.cached_data, self.cached_crc = data, expected
        return "complete"

    def disconnect(self):
        self.active = None


def encode_status(event: int, code: int, ident: int, offset: int, crc: int) -> bytes:
    return STATUS.pack(1, event, code, 0, ident, 32, offset, crc)
