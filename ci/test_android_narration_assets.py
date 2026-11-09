"""Structural/packet tests for the offline remuxer; no decoder or listening claims."""
import importlib.util
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("android_narration_pack", ROOT / "scripts/package-android-explore-narration.py")
pack = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = pack
SPEC.loader.exec_module(pack)


def integer(value):
    parts = [value & 127]
    value >>= 7
    while value:
        parts.insert(0, (value & 127) | 128)
        value >>= 7
    return bytes(parts)


def caf(packets=None, priming=0, remainder=0, valid=None, description=None, table_suffix=b"", data_suffix=b""):
    # Controlled mono20ms TOCs; these packet payloads are never used as decode fixtures.
    packets = packets or [b"\x98" + bytes([index + 1]) * length for index, length in enumerate((9, 254, 300))]
    description = description or struct.pack(">d4sIIIII", 48000, b"opus", 0, 0, 960, 1, 0)
    valid = len(packets) * 960 - priming - remainder if valid is None else valid
    table = struct.pack(">qqii", len(packets), valid, priming, remainder) + b"".join(integer(len(packet)) for packet in packets) + table_suffix
    data = b"\x00" * 4 + b"".join(packets) + data_suffix
    return b"caff\x00\x01\x00\x00" + b"".join(struct.pack(">4sq", kind, len(value)) + value
                                                  for kind, value in ((b"desc", description), (b"data", data), (b"pakt", table)))


def pages(data):
    result, offset = [], 0
    while offset < len(data):
        count = data[offset + 26]
        end = offset + 27 + count + sum(data[offset + 27:offset + 27 + count])
        result.append(bytearray(data[offset:end]))
        offset = end
    return result


def fix_crc(page):
    struct.pack_into("<I", page, 22, 0)
    struct.pack_into("<I", page, 22, pack.ogg_crc(page))


class AndroidNarrationAssetsTest(unittest.TestCase):
    def test_known_source_identity_is_pinned_without_an_environment_override(self):
        self.assertEqual("3bd86116f0d12595965df03cf634066d393b4896", pack.SOURCE_GIT_SHA)
        self.assertEqual("e668885aa69245725d8c6ec3b458efd9b1a7fc792e910161d62e6882c920ffe3", pack.SOURCE_MANIFEST_SHA256)
        self.assertEqual(130, len(pack.SCENES) * len(pack.LOCALES))

    def test_remux_retains_every_packet_boundary_byte_and_order_and_is_deterministic(self):
        original = caf()
        parsed = pack.parse_caf(original)
        result = pack.wrap_ogg(parsed, pack.sha256(original))
        self.assertEqual(result, pack.wrap_ogg(parsed, pack.sha256(original)))
        pack.verify_ogg(result, parsed)
        decoded_packets = [bytes(page[27 + page[26]:]) for page in pages(result)[2:]]
        self.assertEqual(list(parsed.packets), decoded_packets)
        self.assertEqual([10, 255, 301], [len(packet) for packet in decoded_packets])
        self.assertEqual(bytes([255, 0]), bytes(pages(result)[3][27:29]))
        reordered = pack.CafOpus(tuple(reversed(parsed.packets)), parsed.valid_frames, 0, 0)
        with self.assertRaises(pack.PackError):
            pack.verify_ogg(result, reordered)

    def test_caf_metadata_alone_controls_preskip_and_final_granule_without_guessed_encoder_delay(self):
        for priming, remainder in ((0, 0), (120, 100), (960, 959)):
            original = caf(priming=priming, remainder=remainder)
            parsed = pack.parse_caf(original)
            result = pack.wrap_ogg(parsed, pack.sha256(original))
            output = pages(result)
            head = output[0][28:]
            self.assertEqual(priming, struct.unpack_from("<H", head, 10)[0])
            self.assertEqual(2880 - remainder, struct.unpack_from("<Q", output[-1], 6)[0])
            self.assertEqual(2880 - priming - remainder, parsed.valid_frames)
            pack.verify_ogg(result, parsed)

    def test_wrong_codec_rate_channel_frame_size_flags_and_packet_duration_refuse(self):
        variants = [(24000, b"opus", 0, 0, 960, 1, 0), (48000, b"aac ", 0, 0, 960, 1, 0),
                    (48000, b"opus", 0, 0, 960, 2, 0), (48000, b"opus", 0, 0, 480, 1, 0),
                    (48000, b"opus", 1, 0, 960, 1, 0), (48000, b"opus", 0, 20, 960, 1, 0)]
        for description in variants:
            with self.subTest(description=description), self.assertRaises(pack.PackError):
                pack.parse_caf(caf(description=struct.pack(">d4sIIIII", *description)))
        for packet in (b"\x9c\x01", b"\x90\x01", b"\x9b\x00", b"\x9b"):
            with self.subTest(toc=packet[0]), self.assertRaises(pack.PackError):
                pack.parse_caf(caf(packets=[packet]))

    def test_bad_packet_table_edit_count_truncation_duplicate_chunks_and_extra_bytes_refuse(self):
        original = caf()
        bad_edit = bytearray(original)
        location = original.index(b"data") + 12
        bad_edit[location + 3] = 1
        duplicate = original + original[8:52]
        variants = [original[:7], original[:-1], original + b"extra", duplicate, bytes(bad_edit),
                    caf(valid=2879), caf(priming=-1), caf(remainder=960),
                    caf(table_suffix=b"\x00"), caf(data_suffix=b"\x00")]
        for index, bad in enumerate(variants):
            with self.subTest(index=index), self.assertRaises(pack.PackError):
                pack.parse_caf(bad)
        for bad in (b"\x80", b"\x80" * 8):
            with self.assertRaises(pack.PackError):
                pack.varint(bad, 0)

    def test_crc_uses_the_ogg_nonreflected_polynomial_known_check_value(self):
        self.assertEqual(0x89A1897F, pack.ogg_crc(b"123456789"))

    def test_ogg_corruption_fails_even_after_an_attacker_updates_its_crc(self):
        parsed = pack.parse_caf(caf())
        original = pack.wrap_ogg(parsed, pack.sha256(caf()))
        corrupt = bytearray(original)
        corrupt[-1] ^= 1
        with self.assertRaises(pack.PackError):
            pack.verify_ogg(bytes(corrupt), parsed)
        for mutation in ("packet", "granule", "serial", "sequence", "flag", "preskip"):
            output = pages(original)
            target = output[0] if mutation == "preskip" else output[-1]
            if mutation == "packet": target[-1] ^= 1
            elif mutation == "granule": struct.pack_into("<Q", target, 6, 2800)
            elif mutation == "serial": struct.pack_into("<I", target, 14, 123)
            elif mutation == "sequence": struct.pack_into("<I", target, 18, 20)
            elif mutation == "flag": target[5] = 0
            else: struct.pack_into("<H", target, 28 + 10, 312)
            fix_crc(target)
            with self.subTest(mutation=mutation), self.assertRaises(pack.PackError):
                pack.verify_ogg(b"".join(output), parsed)
        with self.assertRaises(pack.PackError):
            pack.verify_ogg(original[:-1], parsed)

    def fixture(self, root):
        audio = caf()
        clips = []
        for scene in sorted(pack.SCENES):
            for locale in sorted(pack.LOCALES):
                name = f"{scene}.{locale}.caf"
                (root / name).write_bytes(audio)
                clips.append({"file": name, "text": f"Caption {scene} {locale}", "locale": locale,
                              "sceneID": scene, "revision": "storytelling-rev-4", "approved": True,
                              "codec": "opus", "container": "caf", "sha256": pack.sha256(audio),
                              "durationSeconds": 0.06})
        source = json.dumps({"schemaVersion": 1, "listeningQualified": True, "clips": clips}).encode()
        (root / "manifest.json").write_bytes(source)
        return source

    def test_complete_pack_preserves_source_approval_bytes_and_every_original_checksum(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = self.fixture(root)
            with patch.object(pack, "SOURCE_MANIFEST_SHA256", pack.sha256(original)):
                output = pack.prepare_pack(root)
            self.assertEqual(original, output["source-manifest.json"])
            self.assertEqual(132, len(output))
            derived = json.loads(output["manifest.json"])
            self.assertFalse(derived["androidListeningQualified"])
            self.assertTrue(derived["sourceListeningQualified"])
            self.assertEqual(pack.sha256(original), derived["sourceManifestSHA256"])
            self.assertEqual(pack.SOURCE_GIT_SHA, derived["sourceGitSHA"])
            for row in derived["clips"]:
                parsed = pack.parse_caf((root / row["sourceFile"]).read_bytes())
                self.assertEqual(pack.sha256((root / row["sourceFile"]).read_bytes()), row["sourceSHA256"])
                self.assertEqual(parsed.packet_sha256, row["packetSHA256"])
                self.assertEqual(pack.sha256(output[row["file"]]), row["sha256"])
                pack.verify_ogg(output[row["file"]], parsed)

    def test_source_pin_missing_locale_unapproved_clip_traversal_and_duration_disagreement_refuse(self):
        for mutation in ("pin", "locale", "approval", "path", "duration", "checksum"):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                original = self.fixture(root)
                manifest = json.loads(original)
                if mutation == "locale": manifest["clips"][-1]["locale"] = "foreign"
                elif mutation == "approval": manifest["clips"][0]["approved"] = False
                elif mutation == "path": manifest["clips"][0]["file"] = "../foreign.caf"
                elif mutation == "duration": manifest["clips"][0]["durationSeconds"] = 0.07
                elif mutation == "checksum": manifest["clips"][0]["sha256"] = "0" * 64
                changed = json.dumps(manifest).encode()
                (root / "manifest.json").write_bytes(changed)
                pin = pack.SOURCE_MANIFEST_SHA256 if mutation == "pin" else pack.sha256(changed)
                with patch.object(pack, "SOURCE_MANIFEST_SHA256", pin), self.subTest(mutation=mutation), self.assertRaises(pack.PackError):
                    pack.prepare_pack(root)

    def test_duplicate_json_keys_are_never_accepted(self):
        with self.assertRaises(pack.PackError):
            json.loads('{"approved":false,"approved":true}', object_pairs_hook=pack.unique_object)

    def test_cli_never_replaces_existing_output_and_verification_rejects_stale_extra_or_changed_files(self):
        with tempfile.TemporaryDirectory() as directory:
            source, destination = Path(directory) / "input", Path(directory) / "output"
            source.mkdir()
            approval = self.fixture(source)
            args = ["--input-root", str(source), "--output-root", str(destination)]
            with patch.object(pack, "SOURCE_MANIFEST_SHA256", pack.sha256(approval)):
                self.assertEqual(0, pack.main(args))
                self.assertEqual(0, pack.main(args + ["--verify"]))
                with self.assertRaises(SystemExit): pack.main(args)
                (destination / "stale.ogg").write_bytes(b"stale")
                with self.assertRaises(SystemExit): pack.main(args + ["--verify"])
                (destination / "stale.ogg").unlink()
                target = destination / "device.en.ogg"
                before = target.read_bytes()
                target.write_bytes(before[:-1] + bytes([before[-1] ^ 1]))
                with self.assertRaises(SystemExit): pack.main(args + ["--verify"])
                self.assertNotEqual(before, target.read_bytes(), "Verification must never repair a changed pack")
                with self.assertRaises(SystemExit):
                    pack.main(["--input-root", str(source), "--output-root", str(source)])
                self.assertEqual(approval, (source / "manifest.json").read_bytes())


if __name__ == "__main__":
    unittest.main()
