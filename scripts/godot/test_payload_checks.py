#!/usr/bin/env python3
"""Pure local tests for the APK boundary checker's ELF alignment guard."""
import struct
import unittest
from check_payload import check_elf


class ElfAlignmentTest(unittest.TestCase):
    def elf(self, alignment=16384, offset=0, address=0):
        data = bytearray(128)
        data[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 1)
        struct.pack_into("<II6Q", data, 64, 1, 5, offset, address, address, 1, 1, alignment)
        return data

    def test_16k_alignment_passes(self):
        check_elf(self.elf())

    def test_4k_alignment_rejected(self):
        with self.assertRaises(ValueError):
            check_elf(self.elf(alignment=4096))

    def test_misaligned_load_rejected(self):
        with self.assertRaises(ValueError):
            check_elf(self.elf(offset=4096))

    def test_non_elf_rejected(self):
        with self.assertRaises(ValueError):
            check_elf(b"not ELF")


if __name__ == "__main__":
    unittest.main()
