#!/usr/bin/env python3
"""Collect only the three admitted public shipping-page screenshots."""
from __future__ import annotations
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import selectors
import struct
import subprocess
import sys
import tarfile
import time
import zlib

TAGS = ("shipping-guard", "shipping-settings", "shipping-upgrade")
FILES = ("manifest.json",) + tuple(tag + ".png" for tag in TAGS)
MAX_PNG = 8 * 1024 * 1024
MAX_TRANSFER = 3 * MAX_PNG + 64 * 1024

class EvidenceError(ValueError):
    pass

def checked_json(data: bytes) -> dict:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise EvidenceError("duplicate manifest field")
            result[key] = value
        return result
    try:
        return json.loads(data, object_pairs_hook=unique)
    except (ValueError, UnicodeError) as error:
        raise EvidenceError("invalid manifest JSON") from error

def png_size(data: bytes) -> tuple[int, int]:
    if not 45 <= len(data) <= MAX_PNG or data[:8] != b"\x89PNG\r\n\x1a\n":
        raise EvidenceError("invalid screenshot PNG")
    offset, size, seen_data, ended = 8, None, False, False
    while offset < len(data):
        if offset + 12 > len(data):
            raise EvidenceError("truncated screenshot PNG")
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        kind = data[offset + 4:offset + 8]
        end = offset + 12 + length
        if end > len(data):
            raise EvidenceError("truncated screenshot PNG")
        payload = data[offset + 8:end - 4]
        if zlib.crc32(kind + payload) & 0xffffffff != struct.unpack(">I", data[end - 4:end])[0]:
            raise EvidenceError("invalid screenshot PNG checksum")
        if size is None:
            if kind != b"IHDR" or length != 13:
                raise EvidenceError("missing screenshot dimensions")
            size = struct.unpack(">II", payload[:8])
            if not 320 <= size[0] <= 4096 or not 480 <= size[1] <= 4096:
                raise EvidenceError("invalid screenshot dimensions")
        elif kind == b"IHDR":
            raise EvidenceError("duplicate screenshot dimensions")
        if kind == b"IDAT":
            seen_data = seen_data or bool(length)
        if kind == b"IEND":
            if length or end != len(data):
                raise EvidenceError("invalid screenshot terminator")
            ended = True
        offset = end
    if not ended or not seen_data or size is None:
        raise EvidenceError("incomplete screenshot PNG")
    return size

def validate_files(files: dict[str, bytes], api: int) -> dict:
    if set(files) != set(FILES) or not 0 < len(files["manifest.json"]) <= 16 * 1024:
        raise EvidenceError("missing or unexpected public capture file")
    manifest = checked_json(files["manifest.json"])
    if (not isinstance(manifest, dict) or set(manifest) != {"schema", "api", "frames"}
            or type(manifest["schema"]) is not int or manifest["schema"] != 1
            or type(manifest["api"]) is not int or manifest["api"] != api or api not in (28, 36)
            or not isinstance(manifest["frames"], list) or len(manifest["frames"]) != 3):
        raise EvidenceError("invalid public capture manifest")
    for tag, frame in zip(TAGS, manifest["frames"]):
        if (not isinstance(frame, dict) or set(frame) != {"tag", "file", "page", "width", "height", "sha256"}
                or frame["tag"] != tag or frame["page"] != tag or frame["file"] != tag + ".png"):
            raise EvidenceError("invalid public capture identity")
        png = files[tag + ".png"]
        width, height = png_size(png)
        if (type(frame["width"]) is not int or type(frame["height"]) is not int
                or (frame["width"], frame["height"]) != (width, height)
                or frame["sha256"] != hashlib.sha256(png).hexdigest()):
            raise EvidenceError("public capture digest or dimension mismatch")
    return manifest

def unpack(data: bytes, api: int) -> dict[str, bytes]:
    if not data or len(data) > MAX_TRANSFER:
        raise EvidenceError("missing or oversized public capture transfer")
    files = {}
    try:
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as archive:
            for item in archive:
                if item.name not in FILES or item.name in files or not item.isfile():
                    raise EvidenceError("unexpected public capture archive member")
                limit = 16 * 1024 if item.name == "manifest.json" else MAX_PNG
                if not 0 < item.size <= limit:
                    raise EvidenceError("oversized public capture archive member")
                source = archive.extractfile(item)
                if source is None:
                    raise EvidenceError("unreadable public capture archive member")
                files[item.name] = source.read(limit + 1)
                if len(files[item.name]) != item.size:
                    raise EvidenceError("truncated public capture archive member")
    except (tarfile.TarError, OSError) as error:
        raise EvidenceError("invalid public capture archive") from error
    validate_files(files, api)
    return files

def bounded_transfer(command: list[str], timeout: float = 15, stdout_limit: int = MAX_TRANSFER) -> bytes:
    child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    output, diagnostics = bytearray(), bytearray()
    deadline = time.monotonic() + timeout
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(child.stdout, selectors.EVENT_READ, output)
            selector.register(child.stderr, selectors.EVENT_READ, diagnostics)
            while selector.get_map():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise EvidenceError("public capture transfer timed out")
                for key, _ in selector.select(min(remaining, 0.25)):
                    block = key.fileobj.read1(64 * 1024)
                    if not block:
                        selector.unregister(key.fileobj)
                        continue
                    key.data.extend(block)
                    if len(output) > stdout_limit or len(diagnostics) > 64 * 1024:
                        raise EvidenceError("public capture transfer exceeded bound")
            if child.wait(timeout=max(0.01, deadline - time.monotonic())) != 0:
                raise EvidenceError("public capture transfer refused")
    except subprocess.TimeoutExpired as error:
        raise EvidenceError("public capture transfer timed out") from error
    finally:
        if child.poll() is None:
            child.kill()
            child.wait(timeout=2)
        child.stdout.close()
        child.stderr.close()
    return bytes(output)

def receipt(directory: Path, api: int, source: str, apk: str, target: str) -> dict:
    if directory.is_symlink() or not directory.is_dir() or set(p.name for p in directory.iterdir()) != set(FILES):
        raise EvidenceError("missing or unexpected retained public capture")
    files = {}
    for name in FILES:
        path = directory / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size > (16 * 1024 if name == "manifest.json" else MAX_PNG):
            raise EvidenceError("invalid retained public capture")
        files[name] = path.read_bytes()
    manifest = validate_files(files, api)
    return {"status": "pass", "source_sha": source, "apk_sha256": apk, "canonical_target": target,
            "api": api, "manifest_sha256": hashlib.sha256(files["manifest.json"]).hexdigest(),
            "frames": manifest["frames"]}

def receipt_valid(value: object, record: dict) -> bool:
    if not isinstance(value, dict) or value.get("status") != "pass":
        return False
    if any(value.get(key) != record.get(key) for key in ("api", "source_sha", "apk_sha256", "canonical_target")):
        return False
    if not isinstance(value.get("manifest_sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", value["manifest_sha256"]):
        return False
    frames = value.get("frames")
    if not isinstance(frames, list) or len(frames) != 3:
        return False
    return all(isinstance(frame, dict) and frame.get("tag") == tag and frame.get("page") == tag
               and frame.get("file") == tag + ".png" and type(frame.get("width")) is int
               and type(frame.get("height")) is int and 320 <= frame["width"] <= 4096
               and 480 <= frame["height"] <= 4096 and isinstance(frame.get("sha256"), str)
               and re.fullmatch(r"[0-9a-f]{64}", frame["sha256"]) is not None
               for tag, frame in zip(TAGS, frames))

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        api_data = bounded_transfer([args.adb, "-s", args.serial, "shell", "getprop", "ro.build.version.sdk"],
                                    timeout=5, stdout_limit=64).strip()
        if api_data not in (b"28", b"36"):
            raise EvidenceError("unsupported public capture API")
        api = int(api_data)
        data = bounded_transfer([args.adb, "-s", args.serial, "exec-out", "run-as", "com.lavasec.app.debug",
                                 "tar", "-C", "cache/predevice-public-screenshots", "-cf", "-", *FILES])
        files = unpack(data, api)
        args.output.mkdir(mode=0o700)
        for name, payload in files.items():
            with (args.output / name).open("xb") as stream:
                stream.write(payload)
            (args.output / name).chmod(0o600)
        print("PUBLIC_UI_CAPTURE: PASS (3 admitted shipping pages)")
        return 0
    except (EvidenceError, OSError) as error:
        # Never include raw device data, stderr, credential values or entered text.
        diagnostic = str(error) if isinstance(error, EvidenceError) else "public capture output unavailable"
        print("PUBLIC_UI_CAPTURE: FAIL (" + diagnostic + ")", file=sys.stderr)
        return 1

if __name__ == "__main__":
    sys.exit(main())
