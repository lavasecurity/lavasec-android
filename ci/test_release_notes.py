import base64
import tempfile
import unittest
from pathlib import Path

from ci.release_notes import encode_notes


class ReleaseNotesTest(unittest.TestCase):
    def encode(self, raw):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "en-US.txt"
            path.write_bytes(raw)
            return encode_notes(path)

    def test_exact_bytes_survive_multiline_unicode_and_shell_characters(self):
        raw = 'DNS protection\n日本語 $HOME `echo nope` $(false)\n'.encode()
        encoded = self.encode(raw)
        self.assertNotIn("\n", encoded)
        self.assertEqual(raw, base64.b64decode(encoded, validate=True))

    def test_play_boundary_counts_characters_not_bytes(self):
        self.assertTrue(self.encode(("界" * 500).encode()))
        with self.assertRaises(ValueError):
            self.encode(("界" * 501).encode())

    def test_empty_controls_and_invalid_utf8_fail_closed(self):
        for raw in (b"", b" \n", b"notes\t", b"notes\r\n", b"\xff", b"notes\x7f"):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                self.encode(raw)

    def test_missing_or_symlinked_notes_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "missing"
            with self.assertRaises(ValueError):
                encode_notes(path)
            target = Path(directory) / "target"
            target.write_text("notes")
            path.symlink_to(target)
            with self.assertRaises(ValueError):
                encode_notes(path)

    def test_checked_in_notes_are_valid(self):
        self.assertTrue(encode_notes(Path(__file__).resolve().parents[1] / "release-notes/en-US.txt"))
