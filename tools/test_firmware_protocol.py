#!/usr/bin/env python3
"""Compare real firmware framing code with an independent Python oracle under ASan/UBSan."""
from pathlib import Path
import random
import shutil
import subprocess
import sys
import tempfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "protocol"))
from reference import ProtocolError, Receiver, fragment  # noqa: E402


def main():
    compiler = shutil.which("clang++") or shutil.which("g++")
    if not compiler:
        raise SystemExit("Install a C++17 compiler (clang++ or g++)")
    lines, expected = [], []
    receiver = Receiver(32768)
    rng = random.Random(986)

    def feed(raw):
        lines.append(raw.hex())
        try:
            complete = receiver.accept(raw)
            valid = True
        except ProtocolError:
            complete, valid = False, False
        expected.append(f"{int(valid)} {int(complete)} {len(receiver.data)} {zlib.crc32(receiver.data)}")

    for ident in range(1, 81):
        lines.append("reset")
        expected.append("reset")
        receiver.reset()
        data = rng.randbytes(rng.choice((1, 9, 300, 1024, 32768)))
        mtu = rng.choice((23, 64, 185, 247, 517))
        for frame in fragment(data, ident, mtu):
            # Invalid input must not consume valid state or cause OOB access.
            if rng.random() < .02:
                feed(frame[:rng.randrange(0, 12)])
                feed(bytes([2]) + frame[1:])
            feed(frame)
            if rng.random() < .07:
                feed(frame)  # retransmitted write after an uncertain acknowledgement
                bad = bytearray(frame)
                bad[-1] ^= 0x80
                feed(bad)  # corrupted duplicate
    # No START, overflowing offset, invalid flags and invalid ATT size.
    for _ in range(200):
        feed(rng.randbytes(rng.randrange(0, 600)))

    with tempfile.TemporaryDirectory(prefix="car-companion-protocol-") as work:
        executable = str(Path(work) / "probe")
        subprocess.run([compiler, "-std=c++17", "-O1", "-g", "-fsanitize=address,undefined", "-fno-omit-frame-pointer",
                        "-I", str(ROOT / "firmware/include"), str(ROOT / "tools/protocol_probe.cpp"),
                        str(ROOT / "firmware/src/Protocol.cpp"), "-o", executable], check=True)
        result = subprocess.run([executable], input="\n".join(lines) + "\n", text=True, capture_output=True, check=True)
        actual = result.stdout.splitlines()
        if len(actual) != len(expected):
            raise AssertionError(f"Output length {len(actual)} != {len(expected)}; {result.stderr}")
        for index, (observed, wanted) in enumerate(zip(actual, expected)):
            if observed != wanted:
                raise AssertionError(f"Frame {index}: {lines[index]}: firmware {observed}, reference {wanted}")
        if result.stderr:
            print(result.stderr, file=sys.stderr)
    print(f"PASS: {len(lines)} production firmware parser operations agree with the independent oracle (ASan + UBSan).")


if __name__ == "__main__":
    main()
