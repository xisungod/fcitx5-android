#!/usr/bin/env python3
"""Encode the production Android renderer's PNG sequences without redrawing its UI."""
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
        "-c:v", "libx264", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
        str(destination / f"{name}.mp4")], check=True)
    palette.unlink()

encode("v14-typing", "continuous-typing")
encode("v14-release", "single-key-release")
for name in ["v14-text-rest", "v14-number-nine-grid", "v14-symbols-chinese", "v14-symbols-recent",
             "v14-symbols-internet", "v14-symbols-internet-page2"]:
    shutil.copy2(args.frames / f"{name}.png", destination / f"{name}.png")
shutil.copy2(args.frames / "v14-typing/preview.txt", destination / "preview-source.txt")
