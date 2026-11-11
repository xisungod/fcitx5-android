#!/usr/bin/env python3
"""Build touch.9 as a Kotlin/resource change on top of the published touch.8.2.2 package.

touch.9 changes no native code, dictionary or model. Its package therefore reuses
every asset and native library of the published touch.8.2.2 APK byte for byte, the
same way the touch.2 to touch.8 packages were produced, and compiles only the
application code and resources of the checked-out tree.

`prepare` downloads and verifies that APK, extracts its assets and libraries,
restores the speech compile JAR and writes the Gradle init script.
`verify` proves that the built APK still carries exactly those assets and libraries.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import subprocess
import sys
import zipfile
from pathlib import Path, PurePosixPath

sys.dont_write_bytecode = True

BASE_APK = {
    "filename": "AXiang-1.2-touch.8.2.2-arm64.apk",
    "url": "https://github.com/xisungod/fcitx5-android/releases/download/"
           "axiang-touch-1.2-touch.8.2.2/AXiang-1.2-touch.8.2.2-arm64.apk",
    "sha256": "deafd181373aeef2094fe9b8a203d16905a21f667c52d292bd9f98c9e1df8282",
    "bytes": 272225182,
}
EXPECTED_COUNTS = {"assets": 351, "lib": 29}
# Source-tracked assets. A change to one of them is a change the reused package
# does not contain, so it must stop the build instead of being silently dropped.
TRACKED_ASSETS = "app/src/main/assets"


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def load(root, name, module_name):
    spec = importlib.util.spec_from_file_location(module_name, root / "scripts" / name)
    require(spec is not None and spec.loader is not None, "Cannot read " + name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def packaged_inputs(archive):
    names = [info.filename for info in archive.infolist() if not info.is_dir()]
    require(len(names) == len(set(names)), "APK contains duplicate ZIP paths")
    selected = sorted(name for name in names if name.startswith(("assets/", "lib/")))
    for name in selected:
        path = PurePosixPath(name)
        require(not path.is_absolute() and ".." not in path.parts and "\\" not in name,
                "Unsafe APK member: " + name)
        if name.startswith("lib/"):
            require(name.startswith("lib/arm64-v8a/") and name.endswith(".so"),
                    "Unexpected native member: " + name)
    counts = {prefix: sum(name.startswith(prefix + "/") for name in selected) for prefix in EXPECTED_COUNTS}
    return selected, counts


def prepare(args):
    root, work = args.root.resolve(), args.work.resolve()
    require((root / "app/build.gradle.kts").is_file(), "--root must be the Android checkout")
    require(work != root and root not in work.parents, "--work must be outside the checkout")
    work.mkdir(parents=True, exist_ok=True)
    stable = load(root, "prepare-axiang-diagnostic-delivery.py", "axiang_stable_delivery")
    source = args.source_apk.resolve() if args.source_apk else stable.fetch(BASE_APK, work / "downloads")
    require(source.stat().st_size == BASE_APK["bytes"] and stable.sha256(source) == BASE_APK["sha256"],
            "Published touch.8.2.2 APK differs")

    prebuilt = work / "prebuilt"
    require(not prebuilt.exists(), "--work already holds extracted inputs; use an empty directory")
    files = []
    with zipfile.ZipFile(source) as archive:
        names, counts = packaged_inputs(archive)
        require(counts == EXPECTED_COUNTS, f"Published asset/native counts differ: {counts}")
        speech = json.loads(archive.read("assets/asr/SOURCE.json"))
        for name in names:
            data = archive.read(name)
            if name.startswith("lib/"):
                require(data[:6] == b"\x7fELF\x02\x01" and int.from_bytes(data[18:20], "little") == 183,
                        "Expected AArch64 ELF64: " + name)
                destination = prebuilt / "jniLibs" / name.removeprefix("lib/")
            else:
                destination = prebuilt / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
            files.append({"path": name, "sha256": digest(data), "bytes": len(data)})

    packaged = {record["path"]: record["sha256"] for record in files}
    tracked = subprocess.check_output(["git", "-C", str(root), "ls-files", "-z", "--", TRACKED_ASSETS])
    changed = []
    for raw in filter(None, tracked.split(b"\0")):
        name = raw.decode("utf-8")
        member = "assets/" + name.removeprefix(TRACKED_ASSETS + "/")
        # A tracked file the published package never carried (a note for developers)
        # is not part of the package either way.
        if member in packaged and digest((root / name).read_bytes()) != packaged[member]:
            changed.append(name)
    require(not changed, "Source assets differ from the reused package; a full data build is needed: "
            + ", ".join(changed))

    expected_jar = speech["compile_jar"]["sha256"]
    runtime_spec, runtime_version = stable.load_runtime_spec(root)
    require(speech["downloads"]["runtime"] == runtime_spec,
            "Published APK and source checkout speech runtime pins disagree")
    jar = root / f"app/libs/sherpa-onnx-{runtime_version}.jar"
    if not (jar.is_file() and stable.sha256(jar) == expected_jar):
        runtime = stable.fetch(runtime_spec, work / "downloads")
        with zipfile.ZipFile(runtime) as archive:
            classes = archive.read("classes.jar")
        require(digest(classes) == expected_jar, "Speech runtime classes.jar differs from the published package")
        jar.parent.mkdir(parents=True, exist_ok=True)
        jar.write_bytes(classes)

    (work / "prebuilt-manifest.json").write_text(json.dumps({
        "schema": "touch9-v1", "source_apk": BASE_APK, "counts": counts, "files": files,
        "compile_jar": {"path": str(jar.relative_to(root)), "sha256": stable.sha256(jar)},
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def groovy(value):
        return json.dumps(str(value), ensure_ascii=False).replace("$", "\\$")

    init = f"""// Published touch.8.2.2 assets and native libraries; compile the current Kotlin and resources.
def verifiedInput = new File({groovy(prebuilt)})
gradle.beforeProject {{ p ->
    p.tasks.configureEach {{ task ->
        def type = task.class
        def nativeInstaller = false
        while (type != null) {{
            if (type.simpleName == 'CMakeBuildInstallTask') nativeInstaller = true
            type = type.superclass
        }}
        if (nativeInstaller) task.enabled = false
    }}
    p.tasks.withType(Test).configureEach {{
        maxHeapSize = '2g'
        // The result files are only reachable as a download; keep each failure readable in the log.
        testLogging {{
            events 'failed'
            exceptionFormat 'full'
            showCauses true
            showStackTraces true
        }}
    }}
    ['com.android.application', 'com.android.library'].each {{ androidPlugin ->
        p.plugins.withId(androidPlugin) {{
            p.extensions.getByName('androidComponents').finalizeDsl {{ dsl ->
                dsl.externalNativeBuild.cmake.path = null
                dsl.buildFeatures.prefab = false
                if (androidPlugin == 'com.android.library') dsl.buildFeatures.prefabPublishing = false
                if (p.path == ':app') {{
                    dsl.buildTypes.getByName('debug').resValue('string', 'app_name', {groovy(args.label).replace('"', "'")})
                    dsl.sourceSets.getByName('main').assets.setSrcDirs([new File(verifiedInput, 'assets')])
                    dsl.sourceSets.getByName('main').jniLibs.setSrcDirs([new File(verifiedInput, 'jniLibs')])
                    dsl.packaging.jniLibs.keepDebugSymbols.add('**/*.so')
                }}
            }}
        }}
    }}
}}
"""
    (work / "touch9.init.gradle").write_text(init, encoding="utf-8")
    print(json.dumps({"counts": counts, "source_apk_sha256": BASE_APK["sha256"],
                      "init": str(work / "touch9.init.gradle")}, ensure_ascii=False))


def verify(args):
    work, apk = args.work.resolve(), args.apk.resolve()
    manifest = json.loads((work / "prebuilt-manifest.json").read_text(encoding="utf-8"))
    expected = {record["path"]: record for record in manifest["files"]}
    with zipfile.ZipFile(apk) as archive:
        names, counts = packaged_inputs(archive)
        require(set(names) == set(expected),
                "Built APK asset/native membership differs: "
                + ", ".join(sorted(set(names) ^ set(expected))[:20]))
        for name in names:
            data = archive.read(name)
            require(len(data) == expected[name]["bytes"] and digest(data) == expected[name]["sha256"],
                    "Built APK changed a reused asset/library: " + name)
        require(any(name.startswith("classes") and name.endswith(".dex") for name in archive.namelist()),
                "Built APK has no compiled application code")
    print(json.dumps({"reused_inputs_byte_identical": len(names), "counts": counts,
                      "apk_sha256": digest(apk.read_bytes()), "apk_bytes": apk.stat().st_size}))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    preparation = commands.add_parser("prepare")
    preparation.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    preparation.add_argument("--work", type=Path, required=True)
    preparation.add_argument("--source-apk", type=Path)
    preparation.add_argument("--label", default="阿翔 触点测试9")
    preparation.set_defaults(run=prepare)
    verification = commands.add_parser("verify")
    verification.add_argument("--work", type=Path, required=True)
    verification.add_argument("--apk", type=Path, required=True)
    verification.set_defaults(run=verify)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
