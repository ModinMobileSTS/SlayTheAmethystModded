#!/usr/bin/env python3
"""Check actual engine/launcher ZIP boundaries, DEX class definitions and ELF load alignment."""
import argparse
from pathlib import Path
import struct
import zipfile


def dex_classes(data):
    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]
    strings, types = u32(0x3c), u32(0x44)
    for index in range(u32(0x60)):
        type_id = u32(u32(0x64) + index * 32)
        string_id = u32(types + type_id * 4)
        offset = u32(strings + string_id * 4)
        while data[offset] & 128:
            offset += 1
        offset += 1
        yield data[offset:data.index(b"\0", offset)].decode("utf8")


def check_elf(data):
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected ELF64 little endian")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    entsize, count = struct.unpack_from("<HH", data, 54)
    for index in range(count):
        header = struct.unpack_from("<II6Q", data, phoff + index * entsize)
        if header[0] == 1 and (header[7] < 16384 or (header[2] - header[3]) % 16384):
            raise ValueError("ELF LOAD segment is not 16 KiB compatible")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", required=True, type=Path)
    parser.add_argument("--launcher", type=Path)
    args = parser.parse_args()
    with zipfile.ZipFile(args.engine) as archive:
        names = archive.namelist()
        assert not any(n.startswith("assets/") and not n.startswith("assets/dexopt/") for n in names), "Engine must not carry game content"
        for name in ["libgodot_android.so", "libc++_shared.so"]:
            check_elf(archive.read("lib/arm64-v8a/" + name))
        print("PASS: engine has no game assets; both native libraries have 16 KiB-compatible LOAD segments")
    if args.launcher:
        with zipfile.ZipFile(args.launcher) as archive:
            assert not any(n.endswith(("libgodot_android.so", ".pck", "engine.apk")) for n in archive.namelist())
            for name in archive.namelist():
                if name.startswith("classes") and name.endswith(".dex"):
                    assert not any(c.startswith(("Lorg/godotengine/", "Lio/stamethyst/godotruntime/")) for c in dex_classes(archive.read(name)))
        print("PASS: launcher has no Godot classes, engine SO, engine container or PCK")


if __name__ == "__main__":
    main()
