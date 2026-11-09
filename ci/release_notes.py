"""Encode the exact checked-in Play notes without shell interpolation or wrapping."""

import argparse
import base64
from pathlib import Path


def encode_notes(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ValueError("release notes must be a regular file")
    raw = path.read_bytes()
    text = raw.decode("utf-8")
    if not text.strip() or len(text) > 500:
        raise ValueError("release notes must contain 1–500 UTF-8 characters")
    if any((ord(c) < 32 and c != "\n") or ord(c) == 127 for c in text):
        raise ValueError("release notes contain a denied control character")
    return base64.b64encode(raw).decode("ascii")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    print(encode_notes(args.path))
