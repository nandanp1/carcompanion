#!/usr/bin/env python3
"""Package source + locally built debug APK/flash files; never includes SDKs, secrets or caches."""
from pathlib import Path
import hashlib
import json
import os
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.1.0"


def source_files():
    """Work from either a Git checkout or the extracted release source tree."""
    yield from (ROOT / name for name in (".gitignore", "LICENSE", "README.md", "THIRD_PARTY_NOTICES.md"))
    ignored_directories = {"build", ".gradle", ".idea", "managed_components", "__pycache__"}
    ignored_files = {".DS_Store", "local.properties", "sdkconfig", "sdkconfig.old", ".env"}
    for directory in (".github", "android", "firmware", "protocol", "docs", "tools"):
        for current, folders, files in os.walk(ROOT / directory):
            folders[:] = sorted(folder for folder in folders if folder not in ignored_directories)
            for name in sorted(files):
                if name not in ignored_files and not name.endswith((".pyc", ".jks", ".keystore")):
                    yield Path(current) / name


def main():
    outputs = {
        "release/Car-Companion-0.1.0-debug.apk": ROOT / "android/app/build/outputs/apk/debug/app-debug.apk",
        "release/firmware/car_companion.bin": ROOT / "firmware/build/car_companion.bin",
        "release/firmware/car_companion-factory.bin": ROOT / "firmware/build/car_companion-flash.bin",
        "release/firmware/bootloader/bootloader.bin": ROOT / "firmware/build/bootloader/bootloader.bin",
        "release/firmware/partition_table/partition-table.bin": ROOT / "firmware/build/partition_table/partition-table.bin",
        "release/firmware/flasher_args.json": ROOT / "firmware/build/flasher_args.json",
        "release/firmware/flash_args": ROOT / "firmware/build/flash_args",
    }
    for file in outputs.values():
        if not file.is_file():
            raise SystemExit(f"Build both projects before packaging: missing {file.relative_to(ROOT)}")
    hashes = {name: hashlib.sha256(file.read_bytes()).hexdigest() for name, file in outputs.items()}
    folder = ROOT / "artifacts"
    folder.mkdir(exist_ok=True)
    target = folder / f"car-companion-{VERSION}.zip"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for file in source_files():
            if file.is_file():
                archive.write(file, f"car-companion/{file.relative_to(ROOT)}")
        for name, file in outputs.items():
            archive.write(file, f"car-companion/{name}")
        archive.writestr("car-companion/release/SHA256SUMS", "".join(f"{digest}  {name.removeprefix('release/')}\n" for name, digest in hashes.items()))
        archive.writestr("car-companion/release/README.txt",
            "Car Companion 0.1.0 / protocol 1.0\n\n"
            "Development APK and ESP32-C6-Touch-AMOLED-2.16 flash files.\n"
            "Read ../README.md and ../docs/hardware-setup.md before use.\n"
            "Read ../docs/validation.md for executed checks and remaining hardware acceptance tests.\n"
            "Install APK: adb install -r Car-Companion-0.1.0-debug.apk\n"
            "Flash (ESP-IDF5.5.3 environment, from release/firmware):\n"
            "python -m esptool --chip esp32c6 --port YOUR_PORT write_flash @flash_args\n"
            "Do not substitute another board. No erase-flash needed for normal updates.\n\n"
            "Initial factory flash alternative (RESETS saved settings and BLE bonds):\n"
            "python -m esptool --chip esp32c6 --port YOUR_PORT write_flash 0x0 car_companion-factory.bin\n")
    (folder / "build-manifest.json").write_text(json.dumps({
        "version": VERSION, "protocol": "1.0", "hardwareAcceptanceComplete": False,
        "validationReport": "docs/validation.md",
        "firmwareSdk": "ESP-IDF 5.5.3", "androidCompileSdk": 36,
        "sha256": hashes, "archiveSha256": hashlib.sha256(target.read_bytes()).hexdigest(),
    }, indent=2) + "\n")
    print(f"Packaged {target.relative_to(ROOT)} ({target.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
