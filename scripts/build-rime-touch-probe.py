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

RIME_REVISION = "de4700e9f6b75b109910613df907965e3cbe0567"
RIME_VERSION = "1.16.1"
RIME_URL = f"https://codeload.github.com/rime/librime/tar.gz/{RIME_REVISION}"
RIME_SHA = "62816f834d938fe40bef86565116826319485c0585d3e2bb1fdd255e3a56308d"
PREBUILT_REVISION = "92ab7d6291a1fa426f199be917857ebb6054d08c"
PREBUILDER_REVISION = "71c6edbf9850209e8c36192ad97ccc55dd25573f"
RIME_PATCH_URL = (f"https://raw.githubusercontent.com/fcitx5-android/prebuilder/"
                  f"{PREBUILDER_REVISION}/patches/librime.patch")
RIME_PATCH_SHA = "fbbc68497ac908a01ce0f6344bf1c5d661b22bd520f0993463313c78f22354e3"
BOOST_URL = "https://github.com/boostorg/boost/releases/download/boost-1.90.0/boost-1.90.0-cmake.tar.xz"
BOOST_SHA = "aca59f889f0f32028ad88ba6764582b63c916ce5f77b31289ad19421a96c555f"
MARISA_REVISION = "3e87d53b78e15f2f43783d5e376561a8c9722051"
MARISA_URL = f"https://codeload.github.com/s-yata/marisa-trie/tar.gz/{MARISA_REVISION}"
MARISA_SHA = "c24516edc43be8049ef4e23e50a574d4670036fe3595c49c0f01d4d87ce58f57"
BASELINE_RIME_SHA = "e81472fd974a557e7233b0a0ca5659fa7da6c4a2eccb5a4a08c9d807d14c7159"
BASELINE_LIBCXX_SHA = "b17919df195f29b0a238468c04171b93cedcdffbe0d30cdc634327cf7f7889bc"
REQUIRED_RTTI_IMPORTS = (
    "_ZTIN4rime13ComponentBaseE",
    "_ZTIN4rime5ClassINS_6ConfigERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEEE9ComponentE",
    "_ZTIN4rime5ClassINS_10TranslatorERKNS_6TicketEE9ComponentE",
    "_ZTIN4rime10TranslatorE",
    "_ZTIN4rime6MemoryE",
)
GLOG_HEADERS = {
    "export.h": "5fb4c1f602bbdf80cfdcd7e22da32d96bd1c96568a914e2f2ce4a880ddc37756",
    "flags.h": "e649cbd1759d9446a53ac643266a2c8ae60c9d005ac2a12a425e50922321fab7",
    "log_severity.h": "e1a6bc7b146d9bc0c54f3740b424561b421181052bad4b0253fc0de066e364ff",
    "logging.h": "59882ed5221cec88eb43af93b60461d74abefe1225c321c8eed9a05e9117b032",
    "platform.h": "941725dc507a8e741adada0af50f721221d96edf0896ce9d822ee0163d0d08e3",
    "raw_logging.h": "af81c12092579db955c541c87a9b5ce54953e26c84346284f2ac92e7fc596baa",
    "stl_logging.h": "4ba223f90ceab8ccb603117f920b3d5d2ad909d91236e00ce6aa22698fcf3f58",
    "types.h": "225bf289f5145549f978a0c87b675d3f0073d1552ac832317d7952f51c3dc114",
    "vlog_is_on.h": "e09f7cd8d599d6a0875d1e435303961fdac2975b5586b581b81d8ccd1229abd1",
}
NDK_VERSION = "28.0.13004108"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def checked_download(cache, name, url, expected):
    destination = cache / name
    if not destination.exists() or digest(destination) != expected:
        temporary = destination.with_suffix(destination.suffix + ".download")
        with urllib.request.urlopen(url, timeout=60) as source, temporary.open("wb") as target:
            shutil.copyfileobj(source, target)
        if digest(temporary) != expected:
            temporary.unlink()
            raise SystemExit(f"Checksum mismatch: {name}")
        temporary.replace(destination)
    return destination


def exact_headers(cache):
    # The packaged static Rime has an Android-specific Engine layout patch.
    # Unpatched upstream headers, even at the same version, are incompatible.
    rime_source = checked_source(cache, "librime-de4700.tar.gz", RIME_URL, RIME_SHA,
                                 f"librime-{RIME_REVISION}/")
    patch = checked_download(cache, "librime-prebuilder.patch", RIME_PATCH_URL, RIME_PATCH_SHA)
    patch_marker = rime_source / ".patched"
    if not patch_marker.exists() or patch_marker.read_text() != RIME_PATCH_SHA:
        subprocess.run(["git", "apply", str(patch.resolve())], cwd=rime_source, check=True)
        patch_marker.write_text(RIME_PATCH_SHA)
    engine = (rime_source / "src/rime/engine.h").read_text()
    if "an<Switcher> switcher_;" not in engine:
        raise SystemExit("Required Android Engine layout patch missing")
    if "set(rime_version 1.16.1)" not in (rime_source / "CMakeLists.txt").read_text():
        raise SystemExit("Pinned source does not identify the packaged runtime")

    archive = checked_download(cache, "boost-1.90.0.tar.xz", BOOST_URL, BOOST_SHA)
    boost = cache / "boost-1.90.0-include"
    marker = boost / ".verified"
    if not marker.exists() or marker.read_text() != BOOST_SHA:
        shutil.rmtree(boost, ignore_errors=True)
        boost.mkdir()
        # Boost's CMake release keeps each library's public headers separately.
        # Merge only include/boost paths; retain their normal boost/ prefix.
        with tarfile.open(archive) as bundle:
            for member in bundle.getmembers():
                if not member.isfile() or "/include/boost/" not in member.name:
                    continue
                relative = Path("boost/" + member.name.split("/include/boost/", 1)[1])
                if relative.is_absolute() or ".." in relative.parts:
                    raise SystemExit("Unsafe Boost archive member")
                target = boost / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                payload = bundle.extractfile(member).read()
                if target.exists() and target.read_bytes() != payload:
                    raise SystemExit("Conflicting Boost public headers")
                target.write_bytes(payload)
        marker.write_text(BOOST_SHA)
    glog = cache / "glog-include"
    (glog / "glog").mkdir(parents=True, exist_ok=True)
    for name, checksum in GLOG_HEADERS.items():
        header = checked_download(cache, "glog-" + name,
            f"https://raw.githubusercontent.com/fcitx5-android/prebuilt/{PREBUILT_REVISION}/"
            f"glog/arm64-v8a/include/glog/{name}", checksum)
        shutil.copyfile(header, glog / "glog" / name)
    marisa = checked_source(cache, "marisa-3e87.tar.gz", MARISA_URL, MARISA_SHA,
                            f"marisa-trie-{MARISA_REVISION}/include/")
    # ComponentBase has only an inline defaulted virtual destructor upstream.
    # That causes every DSO to emit another RTTI identity. A declaration makes
    # it the key function, so this bridge imports the existing destructor/RTTI
    # exported by the unchanged Rime DSO. Layout, signatures and its vtable are
    # identical; production never constructs/deletes registry components.
    # Keep this single-purpose header separate from the exact source snapshot.
    compatibility = cache / "component-rtti-import-include"
    (compatibility / "rime").mkdir(parents=True, exist_ok=True)
    component = (rime_source / "src/rime/component.h").read_text()
    original = "virtual ~ComponentBase() = default;"
    if component.count(original) != 1:
        raise SystemExit("Pinned ComponentBase destructor declaration changed")
    (compatibility / "rime/component.h").write_text(
        component.replace(original, "virtual ~ComponentBase();"))
    translator = (rime_source / "src/rime/translator.h").read_text()
    original = "virtual ~Translator() = default;"
    if translator.count(original) != 1:
        raise SystemExit("Pinned Translator destructor declaration changed")
    (compatibility / "rime/translator.h").write_text(
        translator.replace(original, "virtual ~Translator();"))
    return rime_source / "src", boost, glog, marisa, compatibility


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
    if digest(baseline / "librime.so") != BASELINE_RIME_SHA:
        raise SystemExit("Baseline Rime ELF differs from the verified 1.16.1 Android runtime")
    if digest(baseline / "libc++_shared.so") != BASELINE_LIBCXX_SHA:
        raise SystemExit("Baseline C++ runtime differs from the verified Android ABI")
    rime, boost_parent, glog, marisa, compatibility = exact_headers(args.cache)
    generated = args.cache / "generated"
    (generated / "rime").mkdir(parents=True, exist_ok=True)
    (generated / "rime/build_config.h").write_text(
        "#ifndef RIME_BUILD_CONFIG_H_\n#define RIME_BUILD_CONFIG_H_\n"
        "#define RIME_ENABLE_LOGGING 1\n#define RIME_ALSO_LOG_TO_STDERR 1\n"
        "#define RIME_DATA_DIR \"rime-data\"\n"
        "#define RIME_PLUGINS_DIR \"rime-plugins\"\n#endif\n")
    output.mkdir(parents=True, exist_ok=True)
    source_dir = root / "app/src/main/cpp/typing"
    toolchain = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    native = output / "libaxiangtouch.so"
    command = [str(toolchain / "aarch64-linux-android23-clang++"),
               "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIC", "-shared",
               # Android libc++abi compares these internal Component template
               # RTTI identities across DSOs. Hidden RTTI makes Require's
               # dynamic_cast fail despite a populated main Rime registry.
               "-fvisibility=default", "-ffunction-sections", "-fdata-sections",
               "-DBOOST_DISABLE_CURRENT_LOCATION", "-DBOOST_ALL_NO_EMBEDDED_GDB_SCRIPTS",
               "-DGLOG_USE_GLOG_EXPORT", "-DGLOG_STATIC_DEFINE",
               "-isystem", str(compatibility), "-isystem", str(rime), "-isystem", str(generated),
               "-isystem", str(rime.parent / "include"),
               "-isystem", str(marisa),
               "-isystem", str(boost_parent), "-isystem", str(glog), "-I", str(source_dir),
               str(source_dir / "rime-touch-probe.cpp"), str(source_dir / "rime-touch-jni.cpp"),
               "-L", str(baseline), "-lrime", "-ldl",
               "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
               "-Wl,--gc-sections,--build-id=none,--hash-style=gnu,--no-undefined",
               "-Wl,-soname,libaxiangtouch.so", "-o", str(native)]
    subprocess.run(command, check=True)
    subprocess.run([str(toolchain / "llvm-strip"), "--strip-unneeded", str(native)], check=True)
    probe_symbols = subprocess.check_output(
        [str(toolchain / "llvm-readelf"), "--dyn-symbols", "--wide", str(native)], text=True)
    runtime_symbols = subprocess.check_output(
        [str(toolchain / "llvm-readelf"), "--dyn-symbols", "--wide", str(baseline / "librime.so")], text=True)
    for symbol in REQUIRED_RTTI_IMPORTS:
        imported = [line.split() for line in probe_symbols.splitlines() if line.split() and line.split()[-1] == symbol]
        exported = [line.split() for line in runtime_symbols.splitlines() if line.split() and line.split()[-1] == symbol]
        if len(imported) != 1 or imported[0][-2] != "UND":
            raise SystemExit("Bridge must import the runtime's canonical component RTTI: " + symbol)
        if len(exported) != 1 or exported[0][-2] == "UND" or exported[0][-3] != "DEFAULT":
            raise SystemExit("Runtime must export the required component RTTI: " + symbol)
    alignments = load_alignment(native)
    sources = ["app/src/main/cpp/typing/rime-touch-probe.h",
               "app/src/main/cpp/typing/rime-touch-probe.cpp",
               "app/src/main/cpp/typing/rime-touch-jni.cpp",
               "app/src/main/java/org/fcitx/fcitx5/android/core/RimeTouchProbe.kt",
               "app/src/main/java/org/fcitx/fcitx5/android/core/RimeTouchProbeStatus.kt",
               "scripts/build-rime-touch-probe.py"]
    provenance = {
        "schema": 2, "abi": "arm64-v8a", "ndk_version": NDK_VERSION,
        "android_api": 23, "runtime_version": RIME_VERSION,
        "prebuilt_revision": PREBUILT_REVISION, "prebuilder_revision": PREBUILDER_REVISION,
        "librime_revision": RIME_REVISION,
        "libcxx_sha256": digest(baseline / "libc++_shared.so"),
        "native_sha256": digest(native), "native_bytes": native.stat().st_size,
        "librime_sha256": digest(baseline / "librime.so"),
        "source_files_sha256": {source: digest(root / source) for source in sources},
        "upstream_headers": {"librime": {"url": RIME_URL, "sha256": RIME_SHA},
                             "boost": {"url": BOOST_URL, "sha256": BOOST_SHA, "version": "1.90.0"},
                             "marisa": {"url": MARISA_URL, "sha256": MARISA_SHA, "revision": MARISA_REVISION},
                             "glog": {"prebuilt_revision": PREBUILT_REVISION, "headers_sha256": GLOG_HEADERS}},
        "abi_patch": {"url": RIME_PATCH_URL, "sha256": RIME_PATCH_SHA},
        "generated_build_config_sha256": digest(generated / "rime/build_config.h"),
        "component_rtti_import": {
            "original_header_sha256": digest(rime / "rime/component.h"),
            "bridge_header_sha256": digest(compatibility / "rime/component.h"),
            "translator_original_header_sha256": digest(rime / "rime/translator.h"),
            "translator_bridge_header_sha256": digest(compatibility / "rime/translator.h"),
            "change": "ComponentBase and Translator inline defaulted destructors become external key-function declarations; unchanged Rime exports their existing destructors and RTTI",
            "main_library_modified": False,
            "required_rtti_symbols": list(REQUIRED_RTTI_IMPORTS),
            "all_required_imported_from_runtime": True,
        },
        "abi_header_sha256": {name: digest(rime / name) for name in
            ("rime/engine.h", "rime/context.h", "rime/gear/memory.h", "rime/common.h")},
        "compiler": {"sha256": digest(toolchain / "clang-19"),
                     "version": subprocess.check_output([str(toolchain / "clang-19"), "--version"], text=True).splitlines()[0]},
        "strip": {"sha256": digest(toolchain / "llvm-strip"),
                  "version": subprocess.check_output([str(toolchain / "llvm-strip"), "--version"], text=True).splitlines()[0]},
        "abi_flags": ["--target=aarch64-linux-android23", "-std=c++17", "-fPIC", "-shared",
                      "-fvisibility=default", "-DBOOST_DISABLE_CURRENT_LOCATION",
                      "-DBOOST_ALL_NO_EMBEDDED_GDB_SCRIPTS", "-DGLOG_USE_GLOG_EXPORT", "-DGLOG_STATIC_DEFINE"],
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
