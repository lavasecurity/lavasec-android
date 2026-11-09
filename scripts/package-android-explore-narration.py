#!/usr/bin/env python3
"""Offline, deterministic container remux of the approved frozen Explore pack.

No encoder, network, dependency installation or voice generation is used. CAF packet
bytes are retained exactly; CAF priming/remainder metadata determines Opus pre-skip
and final granule trimming. Independent decoder equivalence is a separate qualification
step. References: RFC 7845 sections 4/5, Apple's CAF specification (desc/data/pakt),
and FFmpeg libavformat/cafdec.c. Never invoked automatically by an app build.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import struct
import tempfile

SOURCE_GIT_SHA = "3bd86116f0d12595965df03cf634066d393b4896"
SOURCE_MANIFEST_SHA256 = "e668885aa69245725d8c6ec3b458efd9b1a7fc792e910161d62e6882c920ffe3"
MAX_AUDIO = 1024 * 1024
MAX_MANIFEST = 256 * 1024
OPUS_VENDOR = b"Lava CAF Opus remux 1"
LOCALES = {"de", "en", "es", "fr", "it", "ja", "ko", "pt-BR", "zh-Hans", "zh-Hant"}
SCENES = {"device", "lookup-intro", "route", "filter-policy", "vpn", "dns-choice",
          "blocked-intro", "blocked-stop", "allowed-intro", "allowed-pass", "vpn-follow",
          "lookup-complete", "ending"}


class PackError(ValueError):
    """Only structural diagnostics, never caption or audio content."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise PackError(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_bounded(path: Path, maximum: int) -> bytes:
    with path.open("rb") as handle:
        data = handle.read(maximum + 1)
    require(len(data) <= maximum, "Input exceeds its bound")
    return data


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON key")
        result[key] = value
    return result


def varint(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    for _ in range(8):
        require(offset < len(data), "Truncated CAF packet table")
        byte = data[offset]
        offset += 1
        value = (value << 7) | (byte & 127)
        if not byte & 128:
            return value, offset
    raise PackError("Oversized CAF packet-table integer")


def opus_samples(packet: bytes) -> int:
    require(bool(packet), "Empty Opus packet")
    toc = packet[0]
    require(not toc & 4, "Only mono Opus packets are admitted")
    config = toc >> 3
    frame = (120, 240, 480, 960)[config & 3] if config >= 16 else (
        (480, 960)[config & 1] if config >= 12 else (480, 960, 1920, 2880)[config & 3])
    code = toc & 3
    if code == 0:
        count = 1
    elif code in (1, 2):
        count = 2
    else:
        require(len(packet) >= 2, "Truncated Opus frame count")
        count = packet[1] & 63
        require(count > 0, "Empty Opus frame sequence")
    require(frame * count <= 5760, "Opus packet duration exceeds 120ms")
    return frame * count


@dataclass(frozen=True)
class CafOpus:
    packets: tuple[bytes, ...]
    valid_frames: int
    priming_frames: int
    remainder_frames: int

    @property
    def packet_sha256(self) -> str:
        return sha256(b"".join(struct.pack(">I", len(packet)) + packet for packet in self.packets))


def parse_caf(data: bytes) -> CafOpus:
    require(8 <= len(data) <= MAX_AUDIO, "Invalid CAF size")
    require(data[:8] == b"caff\x00\x01\x00\x00", "Unsupported CAF header")
    chunks = {}
    offset = 8
    while offset < len(data):
        require(offset + 12 <= len(data), "Truncated CAF chunk header")
        kind, length = struct.unpack_from(">4sq", data, offset)
        offset += 12
        require(kind in {b"desc", b"data", b"pakt", b"chan", b"info"}, "Unsupported CAF chunk")
        require(kind not in chunks and 0 <= length <= len(data) - offset, "Invalid CAF chunk extent")
        chunks[kind] = data[offset:offset + length]
        offset += length
    require({b"desc", b"data", b"pakt"} <= chunks.keys(), "Missing required CAF chunk")
    require(len(chunks[b"desc"]) == 32, "Invalid CAF audio description")
    description = struct.unpack(">d4sIIIII", chunks[b"desc"])
    require(description == (48000.0, b"opus", 0, 0, 960, 1, 0), "Unsupported CAF Opus format")
    table = chunks[b"pakt"]
    require(len(table) >= 24, "Truncated CAF packet metadata")
    count, valid, priming, remainder = struct.unpack_from(">qqii", table)
    require(1 <= count <= 1500 and 0 < valid <= 30 * 48000, "Invalid CAF duration or packet count")
    require(0 <= priming <= 65535 and 0 <= remainder < 960, "Invalid CAF trimming")
    require(valid + priming + remainder == count * 960, "CAF sample count differs from packet count")
    payload = chunks[b"data"]
    require(len(payload) >= 4 and payload[:4] == b"\x00" * 4, "Unsupported CAF edit count")
    cursor, table_cursor = 4, 24
    packets = []
    for _ in range(count):
        length, table_cursor = varint(table, table_cursor)
        require(1 <= length <= 1275 and cursor + length <= len(payload), "Invalid CAF packet extent")
        packet = payload[cursor:cursor + length]
        require(opus_samples(packet) == 960, "CAF packet does not encode 960 frames")
        packets.append(packet)
        cursor += length
    require(cursor == len(payload) and table_cursor == len(table), "Trailing CAF packet data")
    return CafOpus(tuple(packets), valid, priming, remainder)


def ogg_crc(data: bytes) -> int:
    crc = 0
    for byte in data:
        crc ^= byte << 24
        for _ in range(8):
            crc = ((crc << 1) ^ (0x04C11DB7 if crc & 0x80000000 else 0)) & 0xFFFFFFFF
    return crc


def ogg_page(packet: bytes, serial: int, sequence: int, flags: int, granule: int) -> bytes:
    # A zero terminating lace is mandatory when a packet length is a multiple of 255.
    laces = bytes([255] * (len(packet) // 255) + [len(packet) % 255])
    require(len(laces) <= 255, "Packet does not fit a bounded Ogg page")
    header = struct.pack("<4sBBQIIIB", b"OggS", 0, flags, granule, serial, sequence, 0, len(laces))
    page = bytearray(header + laces + packet)
    struct.pack_into("<I", page, 22, ogg_crc(page))
    return bytes(page)


def wrap_ogg(caf: CafOpus, source_sha256: str) -> bytes:
    serial = int(source_sha256[:8], 16) or 1
    head = struct.pack("<8sBBHIhB", b"OpusHead", 1, 1, caf.priming_frames, 48000, 0, 0)
    vendor = OPUS_VENDOR
    tags = b"OpusTags" + struct.pack("<I", len(vendor)) + vendor + struct.pack("<I", 0)
    pages = [ogg_page(head, serial, 0, 2, 0), ogg_page(tags, serial, 1, 0, 0)]
    for index, packet in enumerate(caf.packets):
        last = index == len(caf.packets) - 1
        granule = caf.priming_frames + caf.valid_frames if last else (index + 1) * 960
        pages.append(ogg_page(packet, serial, index + 2, 4 if last else 0, granule))
    result = b"".join(pages)
    require(len(result) <= MAX_AUDIO, "Derived audio exceeds its bound")
    verify_ogg(result, caf)
    return result


def verify_ogg(data: bytes, expected: CafOpus) -> None:
    """Check every page/packet, including boundaries, CRC, headers, and trim metadata."""
    offset, sequence, serial = 0, 0, None
    packets, granules = [], []
    while offset < len(data):
        require(offset + 27 <= len(data), "Truncated Ogg page header")
        magic, version, flags, granule, page_serial, page_sequence, checksum, count = struct.unpack_from("<4sBBQIIIB", data, offset)
        require(magic == b"OggS" and version == 0 and page_sequence == sequence, "Invalid Ogg page identity")
        require(serial is None or serial == page_serial, "Ogg stream serial changed")
        serial = page_serial
        require(offset + 27 + count <= len(data), "Truncated Ogg lacing")
        laces = data[offset + 27:offset + 27 + count]
        require(count > 0 and laces[-1] < 255 and all(lace == 255 for lace in laces[:-1]), "Unexpected Ogg packet boundaries")
        end = offset + 27 + count + sum(laces)
        require(end <= len(data), "Truncated Ogg packet")
        page = bytearray(data[offset:end])
        struct.pack_into("<I", page, 22, 0)
        require(ogg_crc(page) == checksum, "Ogg CRC mismatch")
        expected_flags = 2 if sequence == 0 else (4 if end == len(data) else 0)
        require(flags == expected_flags, "Unexpected Ogg stream flags")
        packets.append(data[offset + 27 + count:end])
        granules.append(granule)
        offset, sequence = end, sequence + 1
    require(len(packets) == len(expected.packets) + 2, "Derived packet count differs")
    head = struct.pack("<8sBBHIhB", b"OpusHead", 1, 1, expected.priming_frames, 48000, 0, 0)
    tags = b"OpusTags" + struct.pack("<I", len(OPUS_VENDOR)) + OPUS_VENDOR + struct.pack("<I", 0)
    require(packets[0] == head and packets[1] == tags, "Invalid derived Opus headers")
    require(tuple(packets[2:]) == expected.packets, "Encoded Opus packets changed")
    require(granules[:2] == [0, 0], "Invalid Ogg header granules")
    expected_granules = [(index + 1) * 960 for index in range(len(expected.packets))]
    expected_granules[-1] = expected.priming_frames + expected.valid_frames
    require(granules[2:] == expected_granules, "Derived granules or end trim changed")


def prepare_pack(input_root: Path) -> dict[str, bytes]:
    original = read_bounded(input_root / "manifest.json", MAX_MANIFEST)
    require(sha256(original) == SOURCE_MANIFEST_SHA256, "Source approval manifest differs from the frozen target")
    manifest = json.loads(original, object_pairs_hook=unique_object)
    require(isinstance(manifest, dict), "Invalid source approval document")
    require(manifest.get("schemaVersion") == 1 and manifest.get("listeningQualified") is True, "Source is not approved")
    clips = manifest.get("clips")
    require(isinstance(clips, list) and len(clips) == 130, "Expected all 130 approved clips")
    identities, output, derived = set(), {"source-manifest.json": original}, []
    for clip in clips:
        require(isinstance(clip, dict), "Invalid source clip")
        scene, locale = clip.get("sceneID"), clip.get("locale")
        require(scene in SCENES and locale in LOCALES and (scene, locale) not in identities, "Invalid scene/locale identity")
        identities.add((scene, locale))
        name = f"{scene}.{locale}.caf"
        require(clip.get("file") == name and clip.get("approved") is True and clip.get("revision") == "storytelling-rev-4", "Unapproved source clip")
        require(clip.get("codec") == "opus" and clip.get("container") == "caf", "Source format changed")
        data = read_bounded(input_root / name, MAX_AUDIO)
        require(sha256(data) == clip.get("sha256"), "Original CAF checksum differs")
        parsed = parse_caf(data)
        duration = clip.get("durationSeconds")
        require(isinstance(duration, (int, float)) and not isinstance(duration, bool) and math.isfinite(duration)
                and abs(duration - parsed.valid_frames / 48000) < 1e-9, "Source duration differs from CAF sample metadata")
        ogg = wrap_ogg(parsed, sha256(data))
        target = name[:-4] + ".ogg"
        output[target] = ogg
        derived.append({**clip, "file": target, "codec": "opus", "container": "ogg", "sha256": sha256(ogg),
                        "sourceFile": name, "sourceSHA256": sha256(data), "packetSHA256": parsed.packet_sha256,
                        "packetCount": len(parsed.packets), "validFrames": parsed.valid_frames,
                        "preSkipFrames": parsed.priming_frames, "endTrimFrames": parsed.remainder_frames})
    require(identities == {(scene, locale) for scene in SCENES for locale in LOCALES}, "Incomplete approved clip matrix")
    result = {"schemaVersion": 1, "sourceGitSHA": SOURCE_GIT_SHA, "sourceManifestSHA256": sha256(original),
              "sourceListeningQualified": True, "androidListeningQualified": False,
              "conversion": "caf-opus-packet-preserving-ogg-v1", "clips": derived}
    output["manifest.json"] = (json.dumps(result, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    require(len(output["manifest.json"]) <= MAX_MANIFEST, "Derived manifest exceeds its bound")
    return output


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-root", required=True, type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--verify", action="store_true", help="Verify an existing derived pack without writing")
    args = parser.parse_args(argv)
    try:
        source, destination = args.input_root.resolve(), args.output_root.resolve()
        require(source != destination and source not in destination.parents, "Output must not modify the source pack")
        output = prepare_pack(source)
        if args.verify:
            require(destination.is_dir() and {path.name for path in destination.iterdir()} == set(output), "Derived pack file set differs")
            for name, expected in output.items():
                require(read_bounded(destination / name, max(MAX_AUDIO, MAX_MANIFEST)) == expected, "Derived file or manifest differs")
        else:
            require(not destination.exists(), "Output already exists; no files were replaced")
            with tempfile.TemporaryDirectory(prefix="lava-narration-", dir=destination.parent) as temporary:
                staged = Path(temporary) / "ExploreNarration"
                staged.mkdir()
                for name, data in output.items():
                    (staged / name).write_bytes(data)
                staged.rename(destination)
        print(json.dumps({"clips": 130, "sourceManifestSHA256": SOURCE_MANIFEST_SHA256,
                          "packetPreserving": True, "androidListeningQualified": False}))
        return 0
    except PackError as error:
        parser.exit(1, f"Narration pack refused: {error}.\n")
    except (OSError, ValueError, KeyError, TypeError, struct.error):
        parser.exit(1, "Narration pack refused: source, packet, metadata or output validation failed.\n")


if __name__ == "__main__":
    raise SystemExit(main())
