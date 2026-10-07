#!/usr/bin/env python3
"""Build one libime prediction JNI bridge against the exact existing APK ABI.

Downloads only pinned upstream headers; existing model/dependency files can be
reused read-only after matching the APK. Does not run Gradle or modify Rime/data.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import tarfile
import urllib.request
import zipfile

BASE_APK_SHA = "5f04bd499df1552878cf353cad882bad709837d6f3ad257edcd34cb26e6eb441"
BASE_APK_BYTES = 268488294
LIBIME_REV = "ecd23795ff7ea63a55a1b88fc4767946b999e102"
FCITX_REV = "1e00551899f9d0fa5418d899f468b6e421401adf"
ARCHIVES = {
    "libime": (LIBIME_REV, "527df7fa0752f9e576054d242f0feb26a450682f82357da1be4a4af4f03b95ab"),
    "fcitx5": (FCITX_REV, "571f44dd03a0cbfd9154e008f66af5e3ba78915c1ed3fdd26005392bb33245a3"),
}
DEPENDENCIES = ("libIMECore.so", "libFcitx5Utils.so", "libc++_shared.so")
MODELS = ("zh_CN.lm", "zh_CN.lm.predict")
SOURCES = ("app/src/main/cpp/axiangpredict.h", "app/src/main/cpp/axiangpredict-text.cpp",
           "app/src/main/cpp/axiangpredict.cpp", "app/src/main/cpp/axiangpredict-jni.cpp",
           "app/src/main/java/org/fcitx/fcitx5/android/core/LibimeNextWordPredictor.kt",
           "app/src/main/cpp/CMakeLists.txt", "app/src/main/cpp/axiangpredict/CMakeLists.txt", "app/build.gradle.kts",
           "scripts/build-axiang-nextword.py")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def headers(work):
    cache = work / "cache"
    cache.mkdir(exist_ok=True)
    for name, (revision, checksum) in ARCHIVES.items():
        archive = cache / (name + "-" + revision + ".tar.gz")
        if not archive.is_file() or digest(archive) != checksum:
            with urllib.request.urlopen(f"https://codeload.github.com/fcitx/{name}/tar.gz/{revision}", timeout=60) as source:
                temporary = archive.with_suffix(".download")
                with temporary.open("wb") as target:
                    shutil.copyfileobj(source, target)
            if digest(temporary) != checksum:
                temporary.unlink()
                raise ValueError("Upstream header archive identity differs")
            temporary.replace(archive)
        folder = cache / (name + "-headers")
        with tarfile.open(archive) as bundle:
            for member in bundle.getmembers():
                relative = Path(*Path(member.name).parts[1:])
                wanted = ((name == "libime" and str(relative).startswith("src/libime/core/") and relative.suffix in (".h", ".hh")) or
                          (name == "fcitx5" and str(relative).startswith("src/lib/fcitx-utils/") and relative.suffix == ".h"))
                if not member.isfile() or not wanted or relative.is_absolute() or ".." in relative.parts:
                    continue
                target = folder / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(bundle.extractfile(member).read())
    # Generated visibility/deprecation declarations affect no layout or ABI.
    exported = cache / "libime-headers/src/libime/core/libimecore_export.h"
    exported.write_text("#pragma once\n#define LIBIMECORE_EXPORT __attribute__((visibility(\"default\")))\n"
                        "#define LIBIMECORE_DEPRECATED __attribute__((deprecated))\n"
                        "#define LIBIMECORE_NO_EXPORT __attribute__((visibility(\"hidden\")))\n"
                        "#define LIBIMECORE_DEPRECATED_EXPORT LIBIMECORE_EXPORT LIBIMECORE_DEPRECATED\n"
                        "#define LIBIMECORE_DEPRECATED_NO_EXPORT LIBIMECORE_NO_EXPORT LIBIMECORE_DEPRECATED\n")
    utils_export = cache / "fcitx5-headers/src/lib/fcitx-utils/fcitxutils_export.h"
    utils_export.write_text("#pragma once\n#define FCITXUTILS_EXPORT __attribute__((visibility(\"default\")))\n"
                            "#define FCITXUTILS_DEPRECATED __attribute__((deprecated))\n"
                            "#define FCITXUTILS_NO_EXPORT __attribute__((visibility(\"hidden\")))\n"
                            "#define FCITXUTILS_DEPRECATED_EXPORT FCITXUTILS_EXPORT FCITXUTILS_DEPRECATED\n"
                            "#define FCITXUTILS_DEPRECATED_NO_EXPORT FCITXUTILS_NO_EXPORT FCITXUTILS_DEPRECATED\n")
    return cache / "libime-headers/src", cache / "fcitx5-headers/src/lib"


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root", type=Path, default=Path.cwd())
    p.add_argument("--work", type=Path, required=True)
    p.add_argument("--apk", type=Path, required=True)
    p.add_argument("--prebuilt", type=Path, help="Existing asset/JNI inputs, reused read-only after exact byte checks")
    p.add_argument("--ndk", type=Path, required=True)
    p.add_argument("--boost-headers", type=Path, required=True,
                   help="Reuse the existing verified Boost include tree; no large dependency copy/download")
    a = p.parse_args()
    a.root = a.root.resolve(); a.work = a.work.resolve(); a.work.mkdir(parents=True, exist_ok=True)
    if digest(a.apk) != BASE_APK_SHA or a.apk.stat().st_size != BASE_APK_BYTES:
        raise ValueError("Expected the actual signed public touch.8 APK")
    if subprocess.check_output(["git", "-C", str(a.root), "ls-tree", "HEAD", "lib/libime/src/main/cpp/libime"], text=True).split()[2] != LIBIME_REV:
        raise ValueError("Libime source ABI revision differs")
    if subprocess.check_output(["git", "-C", str(a.root), "ls-tree", "HEAD", "lib/fcitx5/src/main/cpp/fcitx5"], text=True).split()[2] != FCITX_REV:
        raise ValueError("Fcitx utility ABI revision differs")
    includes = headers(a.work)
    if not (a.boost_headers / "boost/type_traits/add_const.hpp").is_file():
        raise ValueError("Existing Boost headers are required by upstream libime declarations")
    dependency_hashes = {}; model_hashes = {}
    libs = a.prebuilt / "jniLibs/arm64-v8a" if a.prebuilt else a.work / "apk-libs"
    models = a.prebuilt / "assets/usr/share/libime" if a.prebuilt else a.work / "models"
    if not a.prebuilt:
        libs.mkdir(parents=True, exist_ok=True); models.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(a.apk) as archive:
        for names, folder, prefix, records in ((DEPENDENCIES, libs, "lib/arm64-v8a/", dependency_hashes),
                                                (MODELS, models, "assets/usr/share/libime/", model_hashes)):
            for name in names:
                raw = archive.read(prefix + name)
                checksum = hashlib.sha256(raw).hexdigest()
                path = folder / name
                if a.prebuilt:
                    if not path.is_file() or path.is_symlink() or digest(path) != checksum or path.stat().st_size != len(raw):
                        raise ValueError("Reusable input differs: " + name)
                else:
                    path.write_bytes(raw)
                records[name] = {"bytes": len(raw), "sha256": checksum}
    tool = a.ndk / "toolchains/llvm/prebuilt/linux-x86_64"
    flags = ["--target=aarch64-linux-android23", "--sysroot=" + str(tool / "sysroot"), "-std=c++20",
             "-O2", "-fPIC", "-shared", "-nostdlib++", "-H", "-ffile-prefix-map=" + str(a.root) + "=.",
             "-Wl,-z,max-page-size=16384,-z,common-page-size=16384,--build-id=none,--hash-style=gnu,--no-undefined"]
    native = a.work / "libaxiangpredict.so"
    command = [tool / "bin/clang++", *flags]
    for include in (*includes, a.boost_headers):
        command.extend(["-I", include])
    command.extend([a.root / source for source in SOURCES if source.endswith(".cpp")])
    command.extend(["-L", libs, "-lIMECore", "-lc++_shared", "-o", native])
    with (a.work / "compile.log").open("w") as log:
        subprocess.run([str(v) for v in command], check=True, stdout=log, stderr=subprocess.STDOUT)
    # Bind actual included upstream/generated/Boost public declarations without
    # copying or hashing the entire existing dependency tree.
    public_headers = {}
    for line in (a.work / "compile.log").read_text().splitlines():
        if not line.startswith("."):
            continue
        name = line.lstrip(". ")
        path = Path(name)
        if not path.is_file():
            continue
        for include in (*includes, a.boost_headers):
            try:
                relative = path.resolve().relative_to(include.resolve())
            except ValueError:
                continue
            public_headers[str(relative)] = digest(path)
    subprocess.run([tool / "bin/llvm-strip", "--strip-unneeded", native], check=True)
    data = native.read_bytes()
    offset = struct.unpack_from("<Q", data, 32)[0]
    stride, count = struct.unpack_from("<HH", data, 54)
    alignment = [struct.unpack_from("<Q", data, offset + i * stride + 48)[0] for i in range(count)
                 if struct.unpack_from("<I", data, offset + i * stride)[0] == 1]
    if not alignment or min(alignment) < 16384:
        raise ValueError("New native bridge requires 16 KiB ELF load alignment")
    provenance = {"schema": 1, "abi": "arm64-v8a", "android_api": 23, "ndk_version": a.ndk.name,
                  "native_name": native.name, "native_bytes": native.stat().st_size, "native_sha256": digest(native),
                  "base_apk_sha256": BASE_APK_SHA, "libime_revision": LIBIME_REV, "fcitx5_revision": FCITX_REV,
                  "upstream_header_archives": {n: {"revision": r, "sha256": s} for n, (r, s) in ARCHIVES.items()},
                  "dependency_files": dependency_hashes, "model_files": model_hashes,
                  "source_files_sha256": {name: digest(a.root / name) for name in SOURCES},
                  "actually_included_public_headers_sha256": public_headers,
                  "compiler_sha256": digest(tool / "bin/clang++"), "elf_load_alignment": alignment,
                  "cpp_standard": "c++20", "reads_existing_language_model_and_sidecar": True,
                  "user_dictionary_learning": False, "writes_model_or_user_files": False,
                  "rime_initialized_or_modified": False, "neural_model_added": False}
    (a.work / "provenance.json").write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(provenance, ensure_ascii=False))


if __name__ == "__main__":
    main()
