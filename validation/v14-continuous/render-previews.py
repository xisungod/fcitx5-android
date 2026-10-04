#!/usr/bin/env python3
"""Encode true 60fps Android frames; the optional GIF samples every other native frame."""
import argparse
import hashlib
import json
import shutil
import subprocess
import tempfile
from pathlib import Path
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument("frames", type=Path)
args = parser.parse_args()
destination = Path(__file__).resolve().parent
records = []

def run(*args):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-filter_threads", "2",
        "-filter_complex_threads", "2", *map(str, args)], check=True)

def probe(path):
    return json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-count_frames",
        "-select_streams", "v:0", "-show_entries",
        "stream=width,height,nb_read_frames,r_frame_rate:format=duration", "-of", "json", str(path)], text=True))

def encode(folder, name):
    directory = args.frames / folder
    frames = sorted(directory.glob("frame-*.png"))
    assert frames and all(path.name == f"frame-{i:03d}.png" for i, path in enumerate(frames))
    details = dict(row.split("=", 1) for row in (directory / "preview.txt").read_text().splitlines() if "=" in row)
    assert int(details["fps"]) == 60 and int(details["frames"]) == len(frames)
    with tempfile.TemporaryDirectory(prefix="xuancai-continuous-media-") as temporary:
        temporary = Path(temporary)
        source = directory / "frame-%03d.png"
        video = temporary / f"{name}-60fps.mp4"
        run("-threads", 2, "-framerate", 60, "-i", source, "-c:v", "libx264",
            "-threads", 2, "-crf", 18, "-pix_fmt", "yuv420p", "-movflags", "+faststart", video)
        info = probe(video)
        assert int(info["streams"][0]["nb_read_frames"]) == len(frames)
        assert info["streams"][0]["r_frame_rate"] == "60/1"
        assert abs(float(info["format"]["duration"]) - len(frames) / 60) < 0.001
        run("-threads", 2, "-i", video, "-f", "null", "-")
        # GIF cannot represent uniform 1/60-second timing. This labelled fallback
        # uses original even-numbered captures at 30fps, with no interpolation.
        sample = temporary / "gif-frames"
        sample.mkdir()
        sampled = frames[::2]
        for index, path in enumerate(sampled):
            shutil.copy2(path, sample / f"frame-{index:03d}.png")
        palette = temporary / "palette.png"
        gif = temporary / f"{name}-30fps.gif"
        run("-threads", 2, "-framerate", 30, "-i", sample / "frame-%03d.png",
            "-vf", "scale=480:-1:flags=lanczos,palettegen=stats_mode=diff:max_colors=256",
            "-frames:v", 1, "-threads", 2, palette)
        run("-threads", 2, "-framerate", 30, "-i", sample / "frame-%03d.png", "-i", palette,
            "-lavfi", "[0:v]scale=480:-1:flags=lanczos[scaled];[scaled][1:v]paletteuse=dither=bayer:bayer_scale=5",
            "-loop", 0, "-threads", 2, gif)
        with Image.open(gif) as image:
            assert image.n_frames == len(sampled)
            total_ms = 0
            for index in range(image.n_frames):
                image.seek(index)
                total_ms += image.info.get("duration", 0)
        assert abs(total_ms - len(sampled) * 1000 / 30) <= 20
        # Files are copied into the review directory only after validation.
        hashes = {}
        for path in (video, gif):
            target = destination / path.name
            shutil.copy2(path, target)
            assert target.read_bytes() == path.read_bytes()
            hashes[target.name] = {"bytes": target.stat().st_size,
                "sha256": hashlib.sha256(target.read_bytes()).hexdigest()}
        records.append({"name": name, "source": folder, "native_fps": 60,
            "android_frames": len(frames), "video": info, "full_decode": "passed",
            "gif_fps": 30, "gif_native_sampling": "every second original frame",
            "gif_frames": len(sampled), "gif_duration_ms": total_ms, "current_files": hashes})
    for filename in ("preview.txt", "visibility.csv", "ink.csv", "geometry.csv", "motion.csv"):
        if (directory / filename).exists():
            shutil.copy2(directory / filename, destination / f"{name}-{filename}")
    for time in (0, 50, 100, 150, 200, 260, 400, 700, 1200, 2000, 3150, 3900):
        index = round(time * 60 / 1000)
        if index < len(frames):
            shutil.copy2(frames[index], destination / f"{name}-{index * 1000 // 60:04d}ms.png")

encode("v14-continuous-f-on", "v14-continuous-single")
encode("v14-continuous-f-off", "v14-continuous-number-off")
encode("v14-continuous-burst", "v14-continuous-typing")
(destination / "media-check.json").write_text(json.dumps(records, indent=2) + "\n")
