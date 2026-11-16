"""Build only the host deployer from the librime and plugin revisions in the APK."""

from pathlib import Path
import subprocess
import tarfile
from urllib.request import urlopen

from touch_rime_overlay import file_hash, require

ENGINE = "1.12.0"
LUA = "68f9c36"
OCTAGRAM = "dfcc151"
SOURCES = {
    "librime": {
        "url": "https://codeload.github.com/rime/librime/tar.gz/refs/tags/1.12.0",
        "sha256": "e3efe05603e9ec6db058bbff478540c28482513c24662e0aa336d2da72cceee4",
    },
    "librime-lua": {
        "url": "https://codeload.github.com/hchunhui/librime-lua/tar.gz/68f9c36",
        "sha256": "5a5012da02919c78c78f9cedbb5de1ad7b92c92eb2a65be1d1dbe38bd2af73d4",
    },
    "librime-octagram": {
        "url": "https://codeload.github.com/lotem/librime-octagram/tar.gz/dfcc151",
        "sha256": "27f59e184661408b9c689a8c6840452304695251180907570bfb90baab59c81d",
    },
}


def build_deployer(work: Path, workers: int) -> Path:
    source_root = work / "librime"
    build = work / "build"
    work.mkdir(parents=True, exist_ok=True)
    for name, dest in {
        "librime": source_root,
        "librime-lua": source_root / "plugins" / "lua",
        "librime-octagram": source_root / "plugins" / "octagram",
    }.items():
        spec = SOURCES[name]
        archive = work / (name + ".tar.gz")
        if not archive.exists():
            with urlopen(spec["url"], timeout=120) as stream:
                archive.write_bytes(stream.read())
        require(file_hash(archive) == spec["sha256"], "Rime host source hash mismatch: " + name)
        if not (dest / "CMakeLists.txt").exists():
            dest.mkdir(parents=True, exist_ok=True)
            with tarfile.open(archive) as tar:
                for member in tar.getmembers():
                    parts = Path(member.name).parts
                    if len(parts) > 1:
                        member.name = str(Path(*parts[1:]))
                        tar.extract(member, dest, filter="data")

    subprocess.run([
        "cmake", "-S", str(source_root), "-B", str(build), "-GNinja",
        "-DBUILD_TEST=OFF", "-DENABLE_TIMESTAMP=OFF", "-DENABLE_EXTERNAL_PLUGINS=OFF",
        "-DENABLE_LOGGING=OFF", "-DCMAKE_BUILD_TYPE=Release",
    ], check=True)
    subprocess.run(["cmake", "--build", str(build), "--target", "rime_deployer", f"-j{workers}"], check=True)
    return build / "bin" / "rime_deployer"
