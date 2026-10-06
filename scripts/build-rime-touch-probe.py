#!/usr/bin/env python3
"""Build only libaxiangtouch.so against the unchanged baseline Rime addon."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import tarfile
import urllib.request

RIME_URL = "https://codeload.github.com/rime/librime/tar.gz/refs/tags/1.12.0"
RIME_SHA = "e3efe05603e9ec6db058bbff478540c28482513c24662e0aa336d2da72cceee4"
BOOST_URL = "https://archives.boost.io/release/1.86.0/source/boost_1_86_0.tar.bz2"
BOOST_SHA = "1bed88e40401b2cb7a1f76d4bab499e352fa4d0c5f31c0dbae64e24d34d7513b"
NDK_VERSION = "28.0.13004108"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def checked_source(cache, name, url, expected, members_prefix):
    archive = cache / name
    if not archive.exists() or digest(archive) != expected:
        temporary = archive.with_suffix(archive.suffix + ".download")
        with urllib.request.urlopen(url, timeout=60) as source, temporary.open("wb") as target:
            shutil.copyfileobj(source, target)
        if digest(temporary) != expected:
            temporary.unlink()
            raise SystemExit(f"Checksum mismatch: {name}")
        temporary.replace(archive)
    destination = cache / (name + ".headers")
    marker = destination / ".verified"
    if not marker.exists() or marker.read_text() != expected:
        shutil.rmtree(destination, ignore_errors=True)
        destination.mkdir()
        with tarfile.open(archive) as bundle:
            for member in bundle.getmembers():
                if not member.isfile() or not member.name.startswith(members_prefix):
                    continue
                relative = Path(member.name[len(members_prefix):])
                if relative.is_absolute() or ".." in relative.parts:
                    raise SystemExit("Unsafe source archive member")
                target = destination / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                with bundle.extractfile(member) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
        marker.write_text(expected)
    return destination


def load_alignment(path):
    data = path.read_bytes()
    if data[:5] != b"\x7fELF\x02" or data[5] != 1:
        raise SystemExit("Expected little-endian ELF64")
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    alignments = [struct.unpack_from("<Q", data, offset + i * entry_size + 48)[0]
                  for i in range(count)
                  if struct.unpack_from("<I", data, offset + i * entry_size)[0] == 1]
    if not alignments or min(alignments) < 16384:
        raise SystemExit("Native PT_LOAD segments do not support 16 KiB pages")
    return alignments


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--baseline-libs", type=Path, required=True,
                        help="Directory containing unchanged arm64-v8a baseline .so files")
    parser.add_argument("--output", type=Path, required=True,
                        help="Output directory: libaxiangtouch.so and provenance.json")
    parser.add_argument("--cache", type=Path, default=Path("/tmp/axiang-touch2-native-cache"))
    args = parser.parse_args()
    root = args.root.resolve()
    ndk = args.ndk.resolve()
    baseline = args.baseline_libs.resolve()
    output = args.output.resolve()
    if f"Pkg.Revision = {NDK_VERSION}" not in (ndk / "source.properties").read_text():
        raise SystemExit(f"Expected NDK {NDK_VERSION}")
    for name in ["librime.so", "libc++_shared.so"]:
        if not (baseline / name).is_file():
            raise SystemExit(f"Missing baseline dependency: {name}")
    args.cache.mkdir(parents=True, exist_ok=True)
    rime = checked_source(args.cache, "librime-1.12.0.tar.gz", RIME_URL, RIME_SHA,
                          "librime-1.12.0/src/")
    boost = checked_source(args.cache, "boost-1.86.0.tar.bz2", BOOST_URL, BOOST_SHA,
                           "boost_1_86_0/boost/")
    # Preserve the boost/ prefix expected by librime's exact pinned headers.
    boost_parent = args.cache / "boost-include"
    boost_parent.mkdir(exist_ok=True)
    boost_link = boost_parent / "boost"
    if not boost_link.exists():
        boost_link.symlink_to(boost.resolve(), target_is_directory=True)
    generated = args.cache / "generated"
    (generated / "rime").mkdir(parents=True, exist_ok=True)
    (generated / "rime/build_config.h").write_text(
        "#ifndef RIME_BUILD_CONFIG_H_\n#define RIME_BUILD_CONFIG_H_\n"
        "#define RIME_DATA_DIR \"rime-data\"\n"
        "#define RIME_PLUGINS_DIR \"rime-plugins\"\n#endif\n")
    output.mkdir(parents=True, exist_ok=True)
    source_dir = root / "app/src/main/cpp/typing"
    toolchain = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    native = output / "libaxiangtouch.so"
    command = [str(toolchain / "aarch64-linux-android23-clang++"),
               "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIC", "-shared",
               "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections",
               "-isystem", str(rime), "-isystem", str(generated),
               "-isystem", str(boost_parent), "-I", str(source_dir),
               str(source_dir / "rime-touch-probe.cpp"), str(source_dir / "rime-touch-jni.cpp"),
               "-L", str(baseline), "-lrime", "-ldl",
               "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
               "-Wl,--gc-sections,--build-id=none,--hash-style=gnu,--no-undefined",
               "-Wl,-soname,libaxiangtouch.so", "-o", str(native)]
    subprocess.run(command, check=True)
    subprocess.run([str(toolchain / "llvm-strip"), "--strip-unneeded", str(native)], check=True)
    alignments = load_alignment(native)
    sources = ["app/src/main/cpp/typing/rime-touch-probe.h",
               "app/src/main/cpp/typing/rime-touch-probe.cpp",
               "app/src/main/cpp/typing/rime-touch-jni.cpp",
               "app/src/main/java/org/fcitx/fcitx5/android/core/RimeTouchProbe.kt",
               "scripts/build-rime-touch-probe.py"]
    provenance = {
        "schema": 1, "abi": "arm64-v8a", "ndk_version": NDK_VERSION,
        "native_sha256": digest(native), "native_bytes": native.stat().st_size,
        "librime_sha256": digest(baseline / "librime.so"),
        "source_files_sha256": {source: digest(root / source) for source in sources},
        "upstream_headers": {"librime": {"url": RIME_URL, "sha256": RIME_SHA},
                             "boost": {"url": BOOST_URL, "sha256": BOOST_SHA}},
        "elf_load_alignment": alignments,
        "initializes_or_deploys_rime": False,
        "uses_rime_service_session": False,
        "probe_user_dictionary_enabled": False,
    }
    (output / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print(json.dumps({"native": str(native), "provenance": str(output / "provenance.json"),
                      "native_sha256": provenance["native_sha256"]}))


if __name__ == "__main__":
    main()
