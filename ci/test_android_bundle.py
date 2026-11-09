import importlib.util
import hashlib
import json
import struct
import subprocess
import sys
import tempfile
import unittest
import warnings
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

from ci.verify_android_bundle import (
    ABIS,
    DEX_ENTRY,
    EDITION_PACKAGES,
    JS_ASSET,
    JS_SOURCE_MAP_ASSET,
    LIBRARIES,
    REQUIRED_COLOR_RESOURCES,
    verify_platform_colors,
    NARRATION_CLIP_COUNT,
    NARRATION_PACKAGE_MANIFEST_SHA256,
    NARRATION_PREFIX,
    NARRATION_SOURCE_MANIFEST_SHA256,
    NARRATION_SOURCE_SHA,
    REQUIRED_REGISTRATIONS,
    verify_bundle,
)


ROOT = Path(__file__).resolve().parents[1]
RELEASE_VERIFIER = ROOT / "release" / "android" / "verify_android_bundle.py"
# Keep this fixture independent of REQUIRED_REGISTRATIONS: deleting a production
# requirement must not make the corresponding missing-DEX test pass silently.
SHIPPING_REGISTRATIONS = (
    "NativeLavaApp", "LavaTextField", "LavaDecoration", "LavaSlider", "LavaNativePage",
    "LavaShareCardSurface", "LavaShareQr", "LavaBackupCredential", "LavaPasscodeForm",
    "Lcom/swmansion/rnscreens/ScreenFragment;", "Lcom/swmansion/rnscreens/ScreenStackFragment;",
)


def load_release_verifier():
    """Load the self-contained release-copy verifier under a distinct module name."""
    spec = importlib.util.spec_from_file_location(
        "release_verify_android_bundle", RELEASE_VERIFIER
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module



def proto_bytes(field, value):
    def varint(number):
        result = bytearray()
        while number > 127:
            result.append((number & 127) | 128)
            number >>= 7
        return bytes(result) + bytes((number,))
    return varint(field * 8 + 2) + varint(len(value)) + value


# Definitions come from the shipping palette, independently of verifier pins.
SHIPPING_COLORS = {
    color.attrib["name"]
    for color in ET.parse(ROOT / "android/app/src/main/res/values/lava_rn_colors.xml").getroot()
}


def resource_table(names=SHIPPING_COLORS, package=b"com.lavasec.app", type_=b"color", values=True):
    entries = b""
    for name in sorted(names):
        # ConfigValue.value -> Value.item -> Item.prim -> Primitive.color_argb8.
        value = proto_bytes(2, proto_bytes(4, proto_bytes(7, b"\x48\xff\xff\xff\xff\x0f")))
        entry = proto_bytes(2, name.encode())
        if values:
            entry += proto_bytes(6, value)
        entries += proto_bytes(3, entry)
    type_message = proto_bytes(2, type_) + entries
    return proto_bytes(2, proto_bytes(2, package) + proto_bytes(3, type_message))


def dex(strings):
    """Minimal DEX carrying a string table, enough for the verifier's parser."""
    header_size = 112
    string_ids_off = header_size
    data_off = header_size + 4 * len(strings)
    data_off = (data_off + 3) & ~3
    string_data = bytearray()
    offsets = []
    for value in strings:
        encoded = value.encode("utf-8")
        offsets.append(data_off + len(string_data))
        string_data += bytes((len(encoded),)) + encoded + b"\x00"
    file_size = data_off + len(string_data)
    data = bytearray(file_size)
    data[:8] = b"dex\n035\x00"
    struct.pack_into("<I", data, 32, file_size)
    struct.pack_into("<I", data, 36, header_size)
    struct.pack_into("<I", data, 40, 0x12345678)
    struct.pack_into("<II", data, 56, len(strings), string_ids_off)
    for index, offset in enumerate(offsets):
        struct.pack_into("<I", data, string_ids_off + 4 * index, offset)
    struct.pack_into("<II", data, 104, len(string_data), data_off)
    data[data_off:] = string_data
    return bytes(data)


def elf(abi, alignment=16384):
    elf_class, machine = ABIS[abi]
    data = bytearray(128)
    data[:7] = b"\x7fELF" + bytes((elf_class, 1, 1))
    struct.pack_into("<HH", data, 16, 3, machine)
    if elf_class == 2:
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 1)
        struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 128, 128, alignment)
    else:
        struct.pack_into("<I", data, 28, 64)
        struct.pack_into("<HH", data, 42, 32, 1)
        struct.pack_into("<IIIIIIII", data, 64, 1, 0, 0, 0, 128, 128, 5, alignment)
    return bytes(data)


class AndroidBundleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Real committed approved bytes, embedded into the synthetic AAB. No
        # verifier constants or hashes are patched to bless fixture audio.
        assets = ROOT / "android/app/src/main/assets/ExploreNarration"
        cls.narration_entries = {
            "base/assets/ExploreNarration/" + path.name: path.read_bytes()
            for path in assets.iterdir() if path.is_file()
        }

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bundle = self.root / "app.aab"
        self.entries = {
            JS_ASSET: b"console.log('packaged RN');",
            "base/dex/classes.dex": dex(SHIPPING_REGISTRATIONS),
            "base/resources.pb": resource_table(),
        }
        self.entries.update(self.narration_entries)
        self.entries.update({
            f"base/lib/{abi}/{library}": elf(abi) for abi in ABIS for library in LIBRARIES
        })

    def write_bundle(self):
        with zipfile.ZipFile(self.bundle, "w") as archive:
            for path, data in self.entries.items():
                archive.writestr(path, data)

    def test_valid_bundle_with_all_abis_and_exact_generated_inputs(self):
        self.write_bundle()
        js = self.root / "index.android.bundle"
        js.write_bytes(self.entries[JS_ASSET])
        for abi in ABIS:
            for name in LIBRARIES:
                library = self.root / abi / name
                library.parent.mkdir(exist_ok=True)
                library.write_bytes(elf(abi))
        verify_bundle(self.bundle, self.root, js)
        js.write_bytes(b"stale JS")
        with self.assertRaisesRegex(ValueError, "JS differs"):
            verify_bundle(self.bundle, js_bundle=js)
        for name in LIBRARIES:
            library = self.root / "x86" / name
            library.write_bytes(b"stale library")
            with self.subTest(library=name), self.assertRaisesRegex(ValueError, "differs"):
                verify_bundle(self.bundle, jni_root=self.root)
            library.write_bytes(elf("x86"))

    def test_platform_color_contract_covers_both_shipping_palettes(self):
        night = ET.parse(ROOT / "android/app/src/main/res/values-night/lava_rn_colors.xml")
        self.assertEqual(SHIPPING_COLORS, {color.attrib["name"] for color in night.getroot()})
        self.assertEqual(SHIPPING_COLORS, REQUIRED_COLOR_RESOURCES)

    def test_each_edition_requires_its_own_packaged_palette(self):
        for edition, package in (("play", b"com.lavasec.app"),
                                 ("foss", b"com.lavasec.app.foss")):
            with self.subTest(edition=edition):
                self.entries["base/resources.pb"] = resource_table(package=package)
                self.write_bundle()
                verify_bundle(self.bundle, edition=edition)
                other = "foss" if edition == "play" else "play"
                with self.assertRaisesRegex(ValueError, "PlatformColor"):
                    verify_bundle(self.bundle, edition=other)
        # The release controller's existing invocation must remain Play-only.
        with self.assertRaisesRegex(ValueError, "PlatformColor"):
            verify_bundle(self.bundle)

    def test_cli_checks_the_selected_release_edition(self):
        command = [sys.executable, str(Path(__file__).with_name("verify_android_bundle.py")),
                   str(self.bundle)]
        for edition, package in (("play", b"com.lavasec.app"),
                                 ("foss", b"com.lavasec.app.foss")):
            self.entries["base/resources.pb"] = resource_table(package=package)
            self.write_bundle()
            result = subprocess.run(command + ["--edition", edition], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("PlatformColor", result.stderr)

    def test_each_removed_platform_color_is_rejected_after_shrinking(self):
        for edition, package in (("play", b"com.lavasec.app"),
                                 ("foss", b"com.lavasec.app.foss")):
            verify_platform_colors(resource_table(package=package), edition=edition)
            for name in SHIPPING_COLORS:
                with self.subTest(edition=edition, name=name), self.assertRaisesRegex(ValueError, name):
                    verify_platform_colors(resource_table(SHIPPING_COLORS - {name}, package=package),
                                           edition=edition)

    def test_other_packages_cannot_supply_missing_edition_colors(self):
        missing = "lava_rn_focusring"
        for edition, package, other in (("play", b"com.lavasec.app", b"com.lavasec.app.foss"),
                                        ("foss", b"com.lavasec.app.foss", b"com.lavasec.app")):
            table = (resource_table(SHIPPING_COLORS - {missing}, package=package)
                     + resource_table(package=other))
            with self.subTest(edition=edition), self.assertRaisesRegex(ValueError, missing):
                verify_platform_colors(table, edition=edition)

    def test_unsupported_edition_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "unsupported Android edition"):
            verify_platform_colors(resource_table(), edition="debug")

    def test_resource_names_outside_color_definitions_cannot_satisfy_the_gate(self):
        for edition, package in (("play", b"com.lavasec.app"),
                                 ("foss", b"com.lavasec.app.foss")):
            for table in (resource_table(package=b"another.package"),
                          resource_table(package=package, type_=b"string"),
                          resource_table(package=package, values=False),
                          proto_bytes(1, ",".join(sorted(SHIPPING_COLORS)).encode())):
                with self.subTest(edition=edition, table=table[:20]), self.assertRaisesRegex(ValueError, "PlatformColor"):
                    verify_platform_colors(table, edition=edition)

    def test_missing_or_truncated_packaged_resource_table_cannot_ship(self):
        for value in (None, b"\x12\xff", resource_table()[:-1], resource_table(set())):
            if value is None:
                self.entries.pop("base/resources.pb", None)
            else:
                self.entries["base/resources.pb"] = value
            self.write_bundle()
            with self.subTest(value=value and value[:12]), self.assertRaises(ValueError):
                verify_bundle(self.bundle)

    def test_missing_or_empty_js_cannot_ship(self):
        for absent in (True, False):
            if absent:
                self.entries.pop(JS_ASSET)
            else:
                self.entries[JS_ASSET] = b""
            self.write_bundle()
            with self.assertRaisesRegex(ValueError, "JS asset"):
                verify_bundle(self.bundle)

    def test_missing_shrunk_native_registration_cannot_ship(self):
        self.write_bundle()
        verify_bundle(self.bundle)
        for name in SHIPPING_REGISTRATIONS:
            remaining = [value for value in SHIPPING_REGISTRATIONS if value != name]
            self.entries["base/dex/classes.dex"] = dex(remaining)
            self.write_bundle()
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, name):
                verify_bundle(self.bundle)
        self.entries["base/dex/classes.dex"] = dex(SHIPPING_REGISTRATIONS)

    def test_exact_approved_narration_is_read_from_the_artifact(self):
        self.assertEqual(len(self.narration_entries), 132)
        source = self.entries["base/assets/ExploreNarration/source-manifest.json"]
        package = self.entries["base/assets/ExploreNarration/manifest.json"]
        self.assertEqual(hashlib.sha256(source).hexdigest(),
                         "e668885aa69245725d8c6ec3b458efd9b1a7fc792e910161d62e6882c920ffe3")
        self.assertEqual(hashlib.sha256(package).hexdigest(),
                         "5e2aa42dee2de07607e597336b4f7edfc4d087a0da1fad3c3c2b14f13e8a63e0")
        self.assertEqual(json.loads(package)["sourceGitSHA"],
                         "3bd86116f0d12595965df03cf634066d393b4896")
        self.write_bundle()
        verify_bundle(self.bundle)

    def test_missing_packaged_narration_manifest_or_clip_cannot_ship(self):
        for name in ("source-manifest.json", "manifest.json", "device.en.ogg", "ending.zh-Hant.ogg"):
            path = "base/assets/ExploreNarration/" + name
            saved = self.entries.pop(path)
            self.write_bundle()
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "missing.*narration"):
                verify_bundle(self.bundle)
            self.entries[path] = saved

    def test_packaged_audio_cannot_be_empty_tampered_or_swapped_between_locales(self):
        path = "base/assets/ExploreNarration/device.en.ogg"
        original = self.entries[path]
        for data in (b"", original[:-1] + bytes((original[-1] ^ 1,)),
                     self.entries["base/assets/ExploreNarration/device.de.ogg"]):
            self.entries[path] = data
            self.write_bundle()
            with self.subTest(size=len(data)), self.assertRaisesRegex(ValueError, "device.en.ogg.*approved audio"):
                verify_bundle(self.bundle)
        self.entries[path] = original

    def test_rewriting_candidate_manifests_cannot_approve_replacement_audio(self):
        package_path = "base/assets/ExploreNarration/manifest.json"
        source_path = "base/assets/ExploreNarration/source-manifest.json"
        original = self.entries.copy()
        # A self-consistent replacement clip+manifest still lacks approval from
        # the trusted controller. Verifying only candidate-declared hashes would
        # incorrectly accept this AAB.
        package = json.loads(original[package_path])
        clip = package["clips"][0]
        replacement = b"OggS unapproved replacement"
        self.entries["base/assets/ExploreNarration/" + clip["file"]] = replacement
        clip["sha256"] = hashlib.sha256(replacement).hexdigest()
        self.entries[package_path] = json.dumps(package).encode()
        self.write_bundle()
        with self.assertRaisesRegex(ValueError, "manifest.json.*approved manifest"):
            verify_bundle(self.bundle)
        self.entries = original.copy()
        source = json.loads(original[source_path])
        source["listeningQualified"] = False
        self.entries[source_path] = json.dumps(source).encode()
        self.write_bundle()
        with self.assertRaisesRegex(ValueError, "source-manifest.json.*approved manifest"):
            verify_bundle(self.bundle)

    def test_unapproved_or_stale_extra_narration_cannot_ship(self):
        self.entries["base/assets/ExploreNarration/device.en.caf"] = b"stale source audio"
        self.write_bundle()
        with self.assertRaisesRegex(ValueError, "unexpected narration assets"):
            verify_bundle(self.bundle)

    def test_missing_or_corrupt_dex_cannot_ship(self):
        self.entries.pop("base/dex/classes.dex")
        self.write_bundle()
        with self.assertRaisesRegex(ValueError, "DEX"):
            verify_bundle(self.bundle)
        self.entries["base/dex/classes.dex"] = b"not a dex"
        self.write_bundle()
        with self.assertRaisesRegex(ValueError, "expected a DEX"):
            verify_bundle(self.bundle)

    def test_js_source_map_cannot_ship(self):
        self.entries[JS_SOURCE_MAP_ASSET] = b'{"version":3,"sources":["private"]}'
        self.write_bundle()
        with self.assertRaisesRegex(ValueError, "source map"):
            verify_bundle(self.bundle)

    def test_missing_wireguard_or_rn_registration_cannot_ship_on_any_abi(self):
        for abi in ABIS:
            for library, description in LIBRARIES.items():
                path = f"base/lib/{abi}/{library}"
                saved = self.entries.pop(path)
                self.write_bundle()
                with self.subTest(abi=abi, library=library), self.assertRaisesRegex(ValueError, description):
                    verify_bundle(self.bundle)
                self.entries[path] = saved

    def test_wrong_architecture_truncated_and_4k_libraries_cannot_ship(self):
        for library in LIBRARIES:
            path = f"base/lib/arm64-v8a/{library}"
            saved = self.entries[path]
            for invalid in (b"", b"not ELF", elf("x86_64"), elf("arm64-v8a", 4096), elf("arm64-v8a")[:70]):
                with self.subTest(library=library, size=len(invalid)):
                    self.entries[path] = invalid
                    self.write_bundle()
                    with self.assertRaisesRegex(ValueError, library):
                        verify_bundle(self.bundle)
            self.entries[path] = saved

    def test_duplicate_assets_fail_closed(self):
        self.write_bundle()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.bundle, "a") as archive:
                archive.writestr(JS_ASSET, b"other JS")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            verify_bundle(self.bundle)


class AndroidBundleReleaseParityTest(unittest.TestCase):
    """The self-contained release controller copy must not drift from `ci/`."""

    def test_registration_and_asset_contract_matches_the_release_copy(self) -> None:
        release = load_release_verifier()
        # The protected tag-release controller executes its own copy under
        # `trusted-controller/release/android`, so the source CI copy is only
        # meaningful if the release copy pins the exact same DEX/registration
        # contract. Comparing the shared values here fails a PR on drift instead
        # of shipping a release gate weaker than CI.
        self.assertEqual(REQUIRED_COLOR_RESOURCES, release.REQUIRED_COLOR_RESOURCES)
        self.assertEqual(EDITION_PACKAGES, release.EDITION_PACKAGES)
        self.assertEqual(tuple(REQUIRED_REGISTRATIONS), tuple(release.REQUIRED_REGISTRATIONS))
        self.assertEqual(ABIS, release.ABIS)
        self.assertEqual(LIBRARIES, release.LIBRARIES)
        self.assertEqual(JS_ASSET, release.JS_ASSET)
        self.assertEqual(JS_SOURCE_MAP_ASSET, release.JS_SOURCE_MAP_ASSET)
        self.assertEqual(DEX_ENTRY.pattern, release.DEX_ENTRY.pattern)
        self.assertEqual(NARRATION_PREFIX, release.NARRATION_PREFIX)
        self.assertEqual(NARRATION_SOURCE_SHA, release.NARRATION_SOURCE_SHA)
        self.assertEqual(NARRATION_SOURCE_MANIFEST_SHA256, release.NARRATION_SOURCE_MANIFEST_SHA256)
        self.assertEqual(NARRATION_PACKAGE_MANIFEST_SHA256, release.NARRATION_PACKAGE_MANIFEST_SHA256)
        self.assertEqual(NARRATION_CLIP_COUNT, release.NARRATION_CLIP_COUNT)
