#!/usr/bin/env python3
"""Overlay the bounded alphabetic Rime corrector without rebuilding native code."""

import argparse
from pathlib import Path
import sys

sys.dont_write_bytecode = True
from touch_rime_overlay import apply_overlay


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, required=True)
    args = parser.parse_args()
    source = Path(__file__).resolve().parent / "rime"
    names = ("xuancai_correction.lua", "axiang_typo_index.lua")
    apply_overlay(args.work.resolve(), "single-edit-corrections-v1",
                  {"usr/share/rime-data/lua/" + name: source / name for name in names})


if __name__ == "__main__":
    main()
