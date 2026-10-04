#!/usr/bin/env python3
"""Encode unmodified production Android render frames for the corrected reference review."""
import argparse
import json
import shutil
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("frames", type=Path)
args = parser.parse_args()
destination = Path(__file__).resolve().parent
records = []

def encode(folder, name):
    directory = args.frames / folder
    source = directory / "frame-%03d.png"
    frames = sorted(directory.glob("frame-*.png"))
    if not frames:
        raise RuntimeError(f"No Android frames in {directory}")
    palette = destination / f".{name}-palette.png"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", "20", "-i", str(source),
        "-vf", "scale=480:-1:flags=lanczos,palettegen=stats_mode=diff:max_colors=256",
        "-frames:v", "1", "-threads", "2", str(palette)], check=True)
    gif = destination / f"{name}.gif"
    video = destination / f"{name}.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", "20", "-i", str(source),
        "-i", str(palette), "-lavfi",
        "[0:v]scale=480:-1:flags=lanczos[scaled];[scaled][1:v]paletteuse=dither=bayer:bayer_scale=5",
        "-loop", "0", "-threads", "2", str(gif)], check=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", "20", "-i", str(source),
        "-c:v", "libx264", "-threads", "2", "-crf", "18", "-pix_fmt", "yuv420p",
        "-movflags", "+faststart", str(video)], check=True)
    palette.unlink()
    for filename in ("preview.txt", "visibility.csv"):
        if (directory / filename).exists():
            shutil.copy2(directory / filename, destination / f"{name}-{filename}")
    from PIL import Image
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
    assert gif_frames == len(frames) and total_ms == len(frames) * 50
    subprocess.run(["ffmpeg", "-v", "error", "-i", str(video), "-f", "null", "-"], check=True)
    records.append(dict(name=name, source=folder, android_frames=len(frames),
        gif_frames=gif_frames, gif_duration_ms=total_ms, video=info, full_decode="passed"))

encode("v14-f-review-on", "v14-number-row-on")
encode("v14-f-review-off", "v14-number-row-off")
encode("v14-reference-burst", "v14-keyscafe-burst")
for label, source in [("number-row-on", "v14-f-review-on/keyframes/0400-ms.png"),
                      ("number-row-off", "v14-f-review-off/keyframes/0400-ms.png"),
                      ("layout-settings", "light-settings-colours.png")]:
    shutil.copy2(args.frames / source, destination / f"{label}.png")
from PIL import Image, ImageDraw, ImageFont
font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 22)
comparison = Image.new("RGB", (1200, 402), "#0B0C10")
draw = ImageDraw.Draw(comparison)
for i, (name, label) in enumerate([("number-row-on", "NUMBER ROW ON"),
                                    ("number-row-off", "NUMBER ROW OFF")]):
    comparison.paste(Image.open(destination / f"{name}.png").convert("RGB"), (i * 600, 42))
    draw.text((i * 600 + 18, 10), label, fill="#E8E9ED", font=font)
comparison.save(destination / "number-row-comparison.png")
burst = args.frames / "v14-reference-burst"
for time in (250, 700, 1200, 1500, 2400, 4200):
    shutil.copy2(burst / f"frame-{time // 50:03d}.png", destination / f"burst-{time:04d}ms.png")
(destination / "media-check.json").write_text(json.dumps(records, indent=2) + "\n")
