#!/usr/bin/env python3
"""Prove the known ARM64 APK's Rime API version without executing its ELF.

This is deliberately a pinned-binary audit, not a string search or a general
AArch64 emulator. ELF symbols identify RimeGetVersion; its three instructions
identify the returned string. The known binary's API initialization, returned
table address, and dynamic relocation then prove API.get_version uses that
function. Unknown library bytes require a new audit and are rejected.

Usage: python3 scripts/verify-packaged-rime-version.py APK [--output JSON]
Only the APK is read. No library is loaded, extracted, or executed.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import zipfile


PINNED_LIBRARY_SHA256 = (
    "e81472fd974a557e7233b0a0ca5659fa7da6c4a2eccb5a4a08c9d807d14c7159"
)
EXPECTED_VERSION = "1.16.1"
LIBRARY_MEMBER = "lib/arm64-v8a/librime.so"
VERSION_SYMBOL = "_Z14RimeGetVersionv"
API_SYMBOL = "rime_get_api"


class AuditError(Exception):
    """An audit failure whose message contains no local file paths."""


def require(condition, message):
    if not condition:
        raise AuditError(message)


class Elf64:
    def __init__(self, blob):
        self.blob = blob
        require(len(blob) >= 64, "Truncated ELF header")
        header = self.unpack("<16sHHIQQQIHHHHHH", 0)
        ident, kind, machine, version = header[:4]
        require(ident[:4] == b"\x7fELF", "Library is not ELF")
        require(ident[4:7] == b"\x02\x01\x01", "Expected ELF64 little endian")
        require(kind == 3 and machine == 183 and version == 1,
                "Expected AArch64 shared library")
        phoff, shoff = header[5:7]
        ehsize, phsize, phcount, shsize, shcount, _ = header[8:]
        require(ehsize == 64 and phsize == 56 and shsize == 64,
                "Unexpected ELF header sizes")
        require(0 < phcount < 0xffff and 0 < shcount < 0xffff,
                "Missing or extended ELF tables are unsupported")
        self.range(phoff, phcount * phsize)
        self.range(shoff, shcount * shsize)
        self.segments = [self.unpack("<IIQQQQQQ", phoff + i * phsize)
                         for i in range(phcount)]
        self.sections = [self.unpack("<IIQQQQIIQQ", shoff + i * shsize)
                         for i in range(shcount)]
        for segment in self.segments:
            if segment[0] == 1:
                require(segment[5] <= segment[6], "Invalid PT_LOAD sizes")
                self.range(segment[2], segment[5])
        tables = [(i, section) for i, section in enumerate(self.sections)
                  if section[1] == 11]  # SHT_DYNSYM
        require(len(tables) == 1, "Expected one dynamic symbol table")
        self.dynsym_index, symbols = tables[0]
        require(symbols[9] == 24 and symbols[5] % 24 == 0,
                "Invalid dynamic symbol table")
        require(0 <= symbols[6] < len(self.sections), "Invalid symbol string table")
        strings = self.sections[symbols[6]]
        require(strings[1] == 3, "Dynamic symbols need SHT_STRTAB")
        self.range(strings[4], strings[5])
        self.range(symbols[4], symbols[5])
        self.symbols = []
        for offset in range(0, symbols[5], 24):
            name, info, other, section, value, size = self.unpack(
                "<IBBHQQ", symbols[4] + offset)
            require(name < strings[5], "Invalid dynamic symbol name")
            text = self.c_string(strings[4] + name, strings[5] - name)
            self.symbols.append({"name": text, "info": info, "other": other,
                                 "section": section, "value": value, "size": size})

    def range(self, offset, length):
        require(offset >= 0 and length >= 0 and offset <= len(self.blob) - length,
                "ELF data extends outside library")

    def unpack(self, format_string, offset):
        self.range(offset, struct.calcsize(format_string))
        return struct.unpack_from(format_string, self.blob, offset)

    def c_string(self, offset, limit):
        self.range(offset, limit)
        end = self.blob.find(b"\0", offset, offset + limit)
        require(end >= 0, "Unterminated ELF string")
        try:
            return self.blob[offset:end].decode("ascii")
        except UnicodeDecodeError as error:
            raise AuditError("Non-ASCII ELF symbol or version string") from error

    def virtual_offset(self, address, length, flags=0):
        matches = []
        for kind, permissions, offset, virtual, _, file_size, _, _ in self.segments:
            if (kind == 1 and virtual <= address and
                    address + length <= virtual + file_size and
                    permissions & flags == flags):
                matches.append(offset + address - virtual)
        require(len(matches) == 1, "Unmapped or ambiguous ELF virtual address")
        self.range(matches[0], length)
        return matches[0]

    def word(self, address):
        # Instruction evidence must be in a file-backed executable segment.
        return self.unpack("<I", self.virtual_offset(address, 4, flags=1))[0]

    def require_writable_memory(self, address, length):
        # The API table is zero-initialized BSS and need not have file bytes.
        matches = [segment for segment in self.segments
                   if segment[0] == 1 and segment[1] & 6 == 6 and
                   segment[3] <= address and
                   address + length <= segment[3] + segment[6]]
        require(len(matches) == 1, "API table is not in mapped writable memory")

    def function(self, name):
        matches = [symbol for symbol in self.symbols
                   if symbol["name"] == name and symbol["section"] != 0 and
                   symbol["info"] & 15 == 2 and symbol["info"] >> 4 in (1, 2) and
                   symbol["other"] & 3 == 0]
        require(len(matches) == 1, "Missing or ambiguous exported " + name)
        symbol = matches[0]
        self.virtual_offset(symbol["value"], symbol["size"], flags=1)
        return symbol

    def global_data_relocation(self, address, symbol):
        matches = []
        for section in self.sections:
            if section[1] != 4 or section[6] != self.dynsym_index:  # SHT_RELA
                continue
            require(section[9] == 24 and section[5] % 24 == 0,
                    "Invalid dynamic relocation table")
            self.range(section[4], section[5])
            for offset in range(0, section[5], 24):
                location, info, addend = self.unpack("<QQq", section[4] + offset)
                if location != address:
                    continue
                index, kind = info >> 32, info & 0xffffffff
                require(index < len(self.symbols), "Invalid relocation symbol")
                matches.append((kind, self.symbols[index], addend))
        require(len(matches) == 1, "Missing or ambiguous API version relocation")
        kind, relocated, addend = matches[0]
        require(kind == 1025 and relocated == symbol and addend == 0,
                "API version relocation does not bind RimeGetVersion")
        self.virtual_offset(address, 8, flags=4)


def adrp_page(instruction, address, register):
    require(instruction & 0x9f000000 == 0x90000000 and instruction & 31 == register,
            "Unexpected ADRP instruction or register")
    immediate = ((instruction >> 29) & 3) | (((instruction >> 5) & 0x7ffff) << 2)
    if immediate & (1 << 20):
        immediate -= 1 << 21
    return (address & ~4095) + (immediate << 12)


def add_immediate(instruction, source, destination):
    require(instruction & 0xff800000 == 0x91000000 and
            (instruction >> 5) & 31 == source and instruction & 31 == destination,
            "Unexpected ADD instruction or registers")
    return ((instruction >> 10) & 4095) << (12 if instruction & (1 << 22) else 0)


def transfer_offset(instruction, load, source, destination):
    opcode = 0xf9400000 if load else 0xf9000000
    require(instruction & 0xffc00000 == opcode and
            (instruction >> 5) & 31 == source and instruction & 31 == destination,
            "Unexpected API LDR/STR instruction or registers")
    return ((instruction >> 10) & 4095) * 8


def inspect_library(blob, expected_sha256):
    digest = hashlib.sha256(blob).hexdigest()
    require(digest == expected_sha256, "Library does not match expected SHA256")
    # Fixed API initialization addresses are safe only for this exact binary.
    require(digest == PINNED_LIBRARY_SHA256,
            "Unknown library bytes: a new API binding audit is required")
    elf = Elf64(blob)
    version_function = elf.function(VERSION_SYMBOL)
    address = version_function["value"]
    require(version_function["size"] == 12, "Unexpected RimeGetVersion code size")
    instructions = [elf.word(address + i * 4) for i in range(3)]
    require(instructions[2] == 0xd65f03c0, "RimeGetVersion does not return via X30")
    returned = (adrp_page(instructions[0], address, 0) +
                add_immediate(instructions[1], 0, 0))
    version = elf.c_string(elf.virtual_offset(returned, 64, flags=4), 64)
    require(version == EXPECTED_VERSION, "Unexpected packaged Rime API version")

    api = elf.function(API_SYMBOL)
    require(api["value"] == 0x324054 and api["size"] == 1080,
            "Unexpected pinned rime_get_api symbol")
    # Decode X8's API-table address in the initialization branch and X0's
    # returned table address. These locations belong to the pinned function.
    table = (adrp_page(elf.word(0x32406c), 0x32406c, 8) +
             add_immediate(elf.word(0x324070), 8, 8))
    returned_table = (adrp_page(elf.word(0x324480), 0x324480, 0) +
                      add_immediate(elf.word(0x324484), 0, 0))
    cached_table = (adrp_page(elf.word(0x324060), 0x324060, 0) +
                    add_immediate(elf.word(0x324064), 0, 0))
    require(table == returned_table == cached_table and
            elf.word(0x324068) == elf.word(0x324488) == 0xd65f03c0,
            "API initialization and return do not use the same table")
    # X10 = GOT[RimeGetVersion]; API-table X8 + 0x248 = X10.
    got = (adrp_page(elf.word(0x324368), 0x324368, 10) +
           transfer_offset(elf.word(0x32436c), True, 10, 10))
    field_offset = transfer_offset(elf.word(0x32437c), False, 8, 10)
    require(got == 0x500388 and field_offset == 0x248,
            "Unexpected pinned API.get_version binding")
    elf.global_data_relocation(got, version_function)
    elf.require_writable_memory(table + field_offset, 8)
    return {
        "member": LIBRARY_MEMBER,
        "size_bytes": len(blob),
        "sha256": digest,
        "elf_class": 64,
        "elf_machine": "AArch64",
        "version": version,
        "version_function": {
            "dynamic_symbol": VERSION_SYMBOL,
            "address": hex(address),
            "size_bytes": version_function["size"],
            "instructions": [hex(word) for word in instructions],
            "returned_string_address": hex(returned),
        },
        "api_binding": {
            "dynamic_symbol": API_SYMBOL,
            "address": hex(api["value"]),
            "table_address": hex(table),
            "get_version_offset": hex(field_offset),
            "got_address": hex(got),
            "relocation_type": "R_AARCH64_GLOB_DAT",
            "relocation_symbol": VERSION_SYMBOL,
            "verified": True,
        },
    }


def inspect_apk(apk, expected_sha256):
    digest = hashlib.sha256()
    size = 0
    with apk.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
            size += len(chunk)
    with zipfile.ZipFile(apk) as archive:
        members = [member for member in archive.infolist()
                   if member.filename == LIBRARY_MEMBER]
        require(len(members) == 1, "APK must contain one ARM64 librime.so")
        member = members[0]
        require(0 < member.file_size <= 128 * 1024 * 1024,
                "Invalid or excessive packaged library size")
        require(not member.flag_bits & 1, "Encrypted packaged library is unsupported")
        with archive.open(member) as source:
            blob = source.read(member.file_size + 1)
        require(len(blob) == member.file_size, "Packaged library size mismatch")
    return {
        "schema_version": 1,
        "verification_method": "pinned_elf_symbols_instructions_and_api_relocation",
        "executed_native_code": False,
        "apk_sha256": digest.hexdigest(),
        "apk_size_bytes": size,
        "expected_library_sha256": expected_sha256,
        "expected_version": EXPECTED_VERSION,
        "library": inspect_library(blob, expected_sha256),
        "verified": True,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--expected-library-sha256", default=PINNED_LIBRARY_SHA256,
                        help="Additional exact-byte expectation; unknown libraries still fail")
    parser.add_argument("--output", type=Path, help="Write the public JSON evidence to this file")
    arguments = parser.parse_args()
    expected = arguments.expected_library_sha256.lower()
    if re.fullmatch("[0-9a-f]{64}", expected) is None:
        parser.error("expected library SHA256 must contain 64 hexadecimal characters")
    try:
        report = inspect_apk(arguments.apk, expected)
        rendered = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
        if arguments.output is not None:
            arguments.output.write_text(rendered, encoding="utf-8")
        else:
            sys.stdout.write(rendered)
    except AuditError as error:
        print("Packaged Rime audit failed: " + str(error), file=sys.stderr)
        return 1
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile, struct.error):
        print("Packaged Rime audit failed: unreadable or malformed APK/ELF", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
