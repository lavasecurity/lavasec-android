"""Fail closed on missing, redirected, corrupt or unbound shipping captures."""
import copy
import hashlib
import io
import json
from pathlib import Path
import struct
import sys
import tarfile
import tempfile
import unittest
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parent))
import android_public_ui_evidence as ui

def png():
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 360, 640, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress((b"\x00" + b"\x80\x90\xa0\xff" * 360) * 640)) + chunk(b"IEND", b""))

def files(api=28):
    image = png()
    frames = [{"tag": tag, "file": tag + ".png", "page": tag, "width": 360, "height": 640,
               "sha256": hashlib.sha256(image).hexdigest()} for tag in ui.TAGS]
    return {"manifest.json": json.dumps({"schema": 1, "api": api, "frames": frames}).encode(),
            **{tag + ".png": image for tag in ui.TAGS}}

def archive(inputs, extra=None):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as output:
        for name, data in inputs.items():
            item = tarfile.TarInfo(name); item.size = len(data)
            output.addfile(item, io.BytesIO(data))
        if extra is not None:
            output.addfile(extra)
    return stream.getvalue()

class PublicCaptureTests(unittest.TestCase):
    def test_exact_three_real_pngs_roundtrip_with_private_source_bound_receipt(self):
        for api in (28, 36):
            retained = ui.unpack(archive(files(api)), api)
            with tempfile.TemporaryDirectory() as scratch:
                directory = Path(scratch) / "public-ui"; directory.mkdir(mode=0o700)
                for name, data in retained.items():
                    (directory / name).write_bytes(data); (directory / name).chmod(0o600)
                receipt = ui.receipt(directory, api, "a" * 40, "b" * 64, "c" * 40)
                self.assertTrue(ui.receipt_valid(receipt, receipt))
                self.assertEqual(3, len(receipt["frames"]))
                self.assertFalse(ui.receipt_valid(receipt, {**receipt, "apk_sha256": "d" * 64}))
                (directory / ui.FILES[1]).unlink()
                (directory / ui.FILES[1]).symlink_to(directory / ui.FILES[2])
                with self.assertRaises(ui.EvidenceError): ui.receipt(directory, api, "a", "b", "c")

    def test_missing_duplicate_traversal_symlink_and_unexpected_members_refused(self):
        for name in ("../shipping-guard.png", "/shipping-guard.png", "credential.txt", "shipping-guard.png"):
            member = tarfile.TarInfo(name); member.type = tarfile.SYMTYPE; member.linkname = "/etc/passwd"
            with self.subTest(name=name), self.assertRaises(ui.EvidenceError):
                ui.unpack(archive(files(), member), 28)
        incomplete = files(); incomplete.pop("shipping-settings.png")
        with self.assertRaises(ui.EvidenceError): ui.unpack(archive(incomplete), 28)

    def test_digest_dimension_api_schema_frame_order_and_unknown_fields_refused(self):
        for change in (lambda m: m.update(api=36), lambda m: m.update(schema=True),
                       lambda m: m.update(private="must-not-retain"), lambda m: m["frames"].reverse(),
                       lambda m: m["frames"][0].update(sha256="0" * 64),
                       lambda m: m["frames"][0].update(width=1)):
            inputs = files(); manifest = json.loads(inputs["manifest.json"]); change(manifest)
            inputs["manifest.json"] = json.dumps(manifest).encode()
            with self.assertRaises(ui.EvidenceError): ui.unpack(archive(inputs), 28)
        inputs = files(); inputs["manifest.json"] = b'{"schema":1,"schema":1,"api":28,"frames":[]}'
        with self.assertRaises(ui.EvidenceError): ui.unpack(archive(inputs), 28)

    def test_corrupt_truncated_oversized_png_and_archive_refused(self):
        for image in (png()[:-1], png() + b"private-payload", b"not-a-PNG", b"a" * (ui.MAX_PNG + 1)):
            inputs = files(); inputs["shipping-guard.png"] = image
            with self.assertRaises(ui.EvidenceError): ui.unpack(archive(inputs), 28)
        damaged = bytearray(png()); damaged[20] ^= 1
        with self.assertRaises(ui.EvidenceError): ui.png_size(bytes(damaged))
        with self.assertRaises(ui.EvidenceError): ui.unpack(b"a" * (ui.MAX_TRANSFER + 1), 28)

    def test_real_owned_subprocess_transfer_is_bounded_and_refusal_or_timeout_fails(self):
        self.assertEqual(b"admitted", ui.bounded_transfer([sys.executable, "-c", "import sys;sys.stdout.buffer.write(b'admitted')"], 2))
        for script, limit in (("import sys;sys.stderr.write('private-do-not-print');sys.exit(7)", 2),
                              ("import time;time.sleep(30)", 0.1),
                              ("import sys;sys.stdout.buffer.write(b'x' * 26000000)", 2)):
            with self.assertRaises(ui.EvidenceError) as raised:
                ui.bounded_transfer([sys.executable, "-c", script], limit)
            self.assertNotIn("private-do-not-print", str(raised.exception))

if __name__ == "__main__":
    unittest.main()
