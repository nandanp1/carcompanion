import json
from pathlib import Path
import random
import struct
import unittest
import zlib

from reference import ArtworkReceiver, Frame, ProtocolError, Receiver, encode_status, fragment


class WireTests(unittest.TestCase):
    def test_shared_vectors(self):
        vectors = json.loads((Path(__file__).parent / "message-definitions/golden-vectors.json").read_text())
        for vector in vectors["messages"]:
            with self.subTest(vector=vector["name"]):
                raw = bytes.fromhex(vector["payload_hex"])
                frames = list(fragment(raw, vector["id"], vector["mtu"]))
                self.assertEqual([f.hex() for f in frames], vector["frames_hex"])
                receiver = Receiver()
                for frame in frames:
                    receiver.accept(frame)
                self.assertEqual(receiver.data, raw)

    def test_all_supported_mtu_payload_bounds_and_roundtrip(self):
        rng = random.Random(986)
        for mtu in (23, 64, 185, 247, 517):
            for size in (1, 8, 9, 232, 500, 1024, 32768):
                data = rng.randbytes(size)
                receiver = Receiver(32768)
                for frame in fragment(data, 65535, mtu):
                    self.assertLessEqual(len(frame), min(mtu - 3, 512))
                    done = receiver.accept(frame)
                self.assertTrue(done)
                self.assertEqual(data, receiver.data)

    def test_duplicates_are_idempotent_and_corruption_is_rejected(self):
        frames = list(fragment(b"a" * 37, 17))
        receiver = Receiver()
        receiver.accept(frames[0])
        receiver.accept(frames[0])
        self.assertEqual(len(receiver.data), 8)
        with self.assertRaises(ProtocolError):
            receiver.accept(frames[0][:-1] + b"b")
        for frame in frames[1:]:
            receiver.accept(frame)
        self.assertTrue(receiver.accept(frames[-1]))

    def test_gaps_overlap_and_id_change(self):
        frames = list(fragment(b"a" * 30, 1))
        receiver = Receiver()
        receiver.accept(frames[0])
        for invalid in (frames[2], Frame(0, 1, 4, 30, b"a" * 8).encode(), Frame(0, 2, 8, 30, b"a").encode()):
            with self.assertRaises(ProtocolError):
                receiver.accept(invalid)

    def test_malformed_packet_bounds(self):
        good = bytearray(next(fragment(b"abc", 1)))
        cases = [b"", good[:12], bytes([2]) + good[1:], bytes([1, 7]) + good[2:]]
        for offset, value in ((2, b"\0\0"), (4, struct.pack("<I", 0xffffffff)), (8, struct.pack("<I", 32769))):
            raw = bytearray(good)
            raw[offset:offset + len(value)] = value
            cases.append(raw)
        for raw in cases:
            with self.assertRaises(ProtocolError):
                Frame.decode(raw)

    def test_start_end_flags(self):
        for flags in (0, 1, 2):
            with self.assertRaises(ProtocolError):
                Frame(flags, 1, 0, 3, b"abc").encode()

    def test_new_start_replaces_incomplete_json(self):
        receiver = Receiver()
        receiver.accept(next(fragment(b"old incomplete state", 1)))
        self.assertTrue(receiver.accept(next(fragment(b"new", 2))))
        self.assertEqual(receiver.data, b"new")

    def test_json_limit_enforced_before_allocation(self):
        receiver = Receiver()
        with self.assertRaises(ProtocolError):
            receiver.accept(next(fragment(b"a" * 1025, 2)))
        self.assertEqual(len(receiver.data), 0)

    def test_control_status_is_default_mtu_safe(self):
        raw = encode_status(2, 1, 42, 232, 0xcbf43926)
        self.assertEqual(raw.hex(), "010201002a002000e80000002639f4cb")
        self.assertEqual(len(raw), 16)


class ArtworkTests(unittest.TestCase):
    def setUp(self):
        self.receiver = ArtworkReceiver()
        self.data = bytes(range(256)) * 32
        self.crc = zlib.crc32(self.data)

    def transfer(self, ident=1, data=None, crc=None):
        data = self.data if data is None else data
        crc = zlib.crc32(data) if crc is None else crc
        self.receiver.begin(ident, len(data), crc)
        for frame in fragment(data, ident, 247):
            self.receiver.accept(frame)

    def test_crc_standard_vector(self):
        self.assertEqual(zlib.crc32(b"123456789"), 0xcbf43926)

    def test_complete_and_cached_without_retransfer(self):
        self.transfer()
        self.assertEqual(self.receiver.commit(1), "complete")
        self.assertEqual(self.receiver.begin(2, len(self.data), self.crc)[0], "cached")

    def test_crc_failure_keeps_previous_image(self):
        self.transfer()
        self.receiver.commit(1)
        self.transfer(2, b"broken", 123)
        self.assertEqual(self.receiver.commit(2), "bad_crc")
        self.assertEqual(self.receiver.cached_data, self.data)

    def test_decoder_failure_keeps_previous_image(self):
        self.transfer()
        self.receiver.commit(1)
        self.transfer(2, b"not a JPEG")
        self.assertEqual(self.receiver.commit(2, lambda _: False), "decode_failed")
        self.assertEqual(self.receiver.cached_crc, self.crc)

    def test_incomplete_and_resume(self):
        self.receiver.begin(1, len(self.data), self.crc)
        self.receiver.accept(next(fragment(self.data, 1, 247)))
        self.assertEqual(self.receiver.commit(1), "bad_offset")
        self.assertEqual(self.receiver.begin(1, len(self.data), self.crc), ("ready", 232))
        for frame in fragment(self.data, 1, 247, 232):
            self.receiver.accept(frame)
        self.assertEqual(self.receiver.commit(1), "complete")

    def test_disconnect_drops_partial_but_preserves_cache(self):
        self.transfer()
        self.receiver.commit(1)
        self.receiver.begin(2, 8, 123)
        self.receiver.disconnect()
        self.assertIsNone(self.receiver.active)
        self.assertEqual(self.receiver.cached_crc, self.crc)
        self.assertEqual(self.receiver.begin(2, 8, 123), ("ready", 0))

    def test_missing_begin_and_wrong_transfer_rejected(self):
        with self.assertRaises(ProtocolError):
            self.receiver.accept(next(fragment(self.data, 1)))
        self.receiver.begin(1, len(self.data), self.crc)
        with self.assertRaises(ProtocolError):
            self.receiver.accept(next(fragment(self.data, 2)))


if __name__ == "__main__":
    unittest.main()
