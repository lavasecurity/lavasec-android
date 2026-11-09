"""Verify shipped icon binaries, not a font-weight label or an installed host font."""
import hashlib
import json
import re
import struct
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ICONS = ROOT / 'android/design-system/icons'
ASSETS = ROOT / 'android/app/src/main/assets'


def unicode_bmp_glyphs(data, cmap_offset):
    """Read the shipped Unicode format-4 cmap, including its glyph-id array."""
    count = struct.unpack_from('>H', data, cmap_offset + 2)[0]
    for index in range(count):
        platform, encoding, offset = struct.unpack_from('>HHI', data, cmap_offset + 4 + index * 8)
        start = cmap_offset + offset
        if not (platform == 0 or (platform == 3 and encoding == 1)) or struct.unpack_from('>H', data, start)[0] != 4:
            continue
        segments = struct.unpack_from('>H', data, start + 6)[0] // 2
        end_codes = start + 14
        start_codes = end_codes + segments * 2 + 2
        deltas = start_codes + segments * 2
        ranges = deltas + segments * 2
        result = {}
        for segment in range(segments):
            first = struct.unpack_from('>H', data, start_codes + segment * 2)[0]
            last = struct.unpack_from('>H', data, end_codes + segment * 2)[0]
            delta = struct.unpack_from('>h', data, deltas + segment * 2)[0]
            address = ranges + segment * 2
            relative = struct.unpack_from('>H', data, address)[0]
            for codepoint in range(first, last + 1):
                if relative:
                    glyph = struct.unpack_from('>H', data, address + relative + (codepoint - first) * 2)[0]
                    if glyph:
                        glyph = (glyph + delta) & 0xffff
                else:
                    glyph = (codepoint + delta) & 0xffff
                result[codepoint] = glyph
        return result
    raise AssertionError('Packaged font must contain a Unicode BMP cmap')

class MaterialSymbolsTest(unittest.TestCase):
    def test_packaged_fonts_have_static_350_outlines_and_pinned_hashes(self):
        receipt = json.loads((ICONS / 'material-symbols-receipt.json').read_text())
        config = json.loads((ICONS / 'material-symbols.json').read_text())
        self.assertEqual(config['sourceRevision'], receipt['sourceRevision'])
        self.assertEqual(config['sourceSha256'], receipt['sourceSha256'])
        self.assertEqual(hashlib.sha256((ICONS / 'lava-connection-glyphs.json').read_bytes()).hexdigest(),
                         receipt['customGlyphsSha256'])
        self.assertEqual(350, receipt['weight'])
        self.assertEqual(2, len(receipt['files']))
        for name, expected_hash in receipt['files'].items():
            with self.subTest(font=name):
                data = (ASSETS / 'fonts' / name).read_bytes()
                self.assertEqual(expected_hash, hashlib.sha256(data).hexdigest())
                self.assertLess(len(data), 128 * 1024, 'subset must stay bounded')
                count = struct.unpack_from('>H', data, 4)[0]
                tables = {}
                for index in range(count):
                    tag, _, offset, length = struct.unpack_from('>4sIII', data, 12 + index * 16)
                    tables[tag] = (offset, length)
                self.assertNotIn(b'fvar', tables, 'ship fixed outlines; no runtime font-axis fallback')
                weight = struct.unpack_from('>H', data, tables[b'OS/2'][0] + 4)[0]
                self.assertEqual(350, weight)
                self.assertGreater(tables[b'glyf'][1], 0)
                glyphs = unicode_bmp_glyphs(data, tables[b'cmap'][0])
                glyph_count = struct.unpack_from('>H', data, tables[b'maxp'][0] + 4)[0]
                inline = (ROOT / 'android/react-native/canonical/android/material-symbols.ts').read_text()
                codepoints = {int(value, 16) for value in re.findall(r'\\u([0-9a-fA-F]{4})', inline)}
                custom = json.loads((ICONS / 'lava-connection-glyphs.json').read_text())['glyphs']
                codepoints.update(int(glyph['codepoint'], 16) for glyph in custom.values())
                for codepoint in codepoints:
                    self.assertGreater(glyphs.get(codepoint, 0), 0, f'{name}: U+{codepoint:04X} is missing')
                    self.assertLess(glyphs[codepoint], glyph_count, f'{name}: U+{codepoint:04X} has an invalid glyph ID')

    def test_native_and_inline_aliases_cover_the_same_semantic_catalog(self):
        aliases = json.loads((ICONS / 'material-symbols.json').read_text())['symbols']
        native = (ROOT / 'android/app/src/main/kotlin/com/lavasec/app/reactnative/MaterialSymbolGlyphs.kt').read_text()
        inline = (ROOT / 'android/react-native/canonical/android/material-symbols.ts').read_text()
        for alias in aliases:
            self.assertIn(json.dumps(alias) + ' to ', native)
            self.assertIn(json.dumps(alias) + ':', inline)
        native_points = dict(re.findall(r'"([^"]+)" to "\\u([0-9a-fA-F]{4})"', native))
        inline_points = dict(re.findall(r'"([^"]+)":"\\u([0-9a-fA-F]{4})"', inline))
        self.assertEqual(set(aliases), set(native_points))
        self.assertEqual(native_points, inline_points)
        self.assertEqual((ICONS / 'MaterialSymbols-LICENSE.txt').read_bytes(),
                         (ASSETS / 'licenses/MATERIAL-SYMBOLS-LICENSE').read_bytes())

if __name__ == '__main__':
    unittest.main()
