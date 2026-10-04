#!/usr/bin/env python3
"""Encode original 30fps native Android review frames without motion interpolation."""
import argparse
import json
import shutil
import subprocess
from pathlib import Path
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument("frames", type=Path)
args = parser.parse_args()
destination = Path(__file__).resolve().parent
records = []

def encode(folder, name):
    directory = args.frames / folder
    frames = sorted(directory.glob("frame-*.png"))
    if not frames:
        raise RuntimeError(f"No native Android frames in {directory}")
    source = directory / "frame-%03d.png"
    palette = destination / f".{name}-palette.png"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", "30", "-i", str(source),
        "-vf", "scale=480:-1:flags=lanczos,palettegen=stats_mode=diff:max_colors=256",
        "-frames:v", "1", "-threads", "2", str(palette)], check=True)
    # Interrupted encoding must never replace an already verified deliverable.
    gif = destination / f".{name}.partial.gif"
    video = destination / f".{name}.partial.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", "30", "-i", str(source),
        "-i", str(palette), "-lavfi",
        "[0:v]scale=480:-1:flags=lanczos[scaled];[scaled][1:v]paletteuse=dither=bayer:bayer_scale=5",
        "-loop", "0", "-threads", "2", str(gif)], check=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", "30", "-i", str(source),
        "-c:v", "libx264", "-threads", "2", "-crf", "18", "-pix_fmt", "yuv420p",
        "-movflags", "+faststart", str(video)], check=True)
    palette.unlink()
    for path in directory.glob("*.csv"):
        shutil.copy2(path, destination / f"{name}-{path.name}")
    if (directory / "preview.txt").exists():
        shutil.copy2(directory / "preview.txt", destination / f"{name}-preview.txt")
    with Image.open(gif) as image:
        gif_frames = image.n_frames
        total_ms = 0
        for i in range(gif_frames):
            image.seek(i)
            total_ms += image.info.get("duration", 0)
    info = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-count_frames",
        "-select_streams", "v:0", "-show_entries",
        "stream=width,height,nb_read_frames:format=duration", "-of", "json", str(video)], text=True))
    assert int(info["streams"][0]["nb_read_frames"]) == len(frames)
    assert gif_frames == len(frames)
    # GIF timestamps have centisecond precision; MP4 preserves exactly 30fps.
    assert abs(total_ms - len(frames) * 1000 / 30) <= 20
    subprocess.run(["ffmpeg", "-v", "error", "-i", str(video), "-f", "null", "-"], check=True)
    gif.replace(destination / f"{name}.gif")
    video.replace(destination / f"{name}.mp4")
    records.append(dict(name=name, source=folder, android_frames=len(frames), fps=30,
        gif_frames=gif_frames, gif_duration_ms=total_ms, video=info, full_decode="passed"))
    for time in (0, 100, 200, 300, 500, 700, 1200, 2000, 3200, 4200):
        index = round(time * 30 / 1000)
        if index < len(frames):
            shutil.copy2(frames[index], destination / f"{name}-{time:04d}ms.png")

encode("v14-stable-f-on", "v14-stable-single")
encode("v14-stable-f-off", "v14-stable-number-off")
encode("v14-stable-burst", "v14-stable-typing")
(destination / "media-check.json").write_text(json.dumps(records, indent=2) + "\n")
