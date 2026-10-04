#!/usr/bin/env python3
"""Encode actual Android PNG sequences without redrawing or changing their colours."""
import argparse
import shutil
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("frames", type=Path, help="app/build/outputs/effect-checks")
args = parser.parse_args()
destination = Path(__file__).resolve().parent

def encode(folder: str, name: str):
    source = args.frames / folder / "frame-%03d.png"
    palette = destination / f".{name}-palette.png"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", "20", "-i", str(source),
        "-vf", "scale=480:-1:flags=lanczos,palettegen=stats_mode=diff:max_colors=256", "-frames:v", "1", str(palette)], check=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", "20", "-i", str(source), "-i", str(palette),
        "-lavfi", "[0:v]scale=480:-1:flags=lanczos[scaled];[scaled][1:v]paletteuse=dither=bayer:bayer_scale=5",
        "-loop", "0", str(destination / f"{name}.gif")], check=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", "20", "-i", str(source),
        "-c:v", "libx264", "-threads", "2", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
        str(destination / f"{name}.mp4")], check=True)
    palette.unlink()
    for filename in ("preview.txt", "visibility.csv"):
        shutil.copy2(args.frames / folder / filename, destination / f"{name}-{filename}")

encode("v14-soft-typing", "v14-soft-continuous")
encode("v14-soft-release", "v14-soft-single-press")
for filename in ("light-settings-colours", "light-settings-timing", "light-settings-colour-editor"):
    shutil.copy2(args.frames / f"{filename}.png", destination / f"{filename}.png")
