#!/usr/bin/env python3
"""Reject missing Android runtime registrations, native engines or approved narration.

The release controller owns this check; it never executes candidate code. Source
CI carries the same check for early feedback. Optional source paths also prove
that Gradle packaged the freshly built bytes, rather than stale generated assets.

The packaged registration is proven in the release artifact itself: every
JS-facing name the RN host registers must be present in the shrunk DEX.
A debug assembly would miss R8 stripping a registration class, so this reads the
AAB's `base/dex/classes*.dex` string table rather than trusting the build graph.
Narration checks read the actual packaged manifests and every clip against fixed
approval hashes; a candidate cannot approve replacement audio by rewriting its
own manifest. This proves asset identity, not Android listening qualification.
"""

import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path


ABIS = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86_64": (2, 62), "x86": (1, 3)}
LIBRARIES = {
    "liblavasec_wireguard_core.so": "WireGuard engine",
    "liblavasec_doq_core.so": "DoQ QUIC engine",
    "libappmodules.so": "React Native appmodules registration",
}
JS_ASSET = "base/assets/index.android.bundle"
# A published JS source map would disclose the private RN source tree; the
# bundle must never carry one (F24 export/source-map boundary).
JS_SOURCE_MAP_ASSET = JS_ASSET + ".map"
DEX_ENTRY = re.compile(r"base/dex/classes\d*\.dex")
EDITION_PACKAGES = {"play": b"com.lavasec.app", "foss": b"com.lavasec.app.foss"}
# JS-facing registration names the Android RN host must expose after shrinking:
# the `NativeLavaApp` module and every native Android component the shared graph
# resolves through `requireNativeComponent`.
REQUIRED_REGISTRATIONS = (
    "NativeLavaApp",
    "LavaTextField",
    "LavaDecoration",
    "LavaSlider",
    "LavaNativePage",
    "LavaShareCardSurface",
    "LavaShareQr",
    "LavaBackupCredential",
    "LavaPasscodeForm",
    # The restoration factory branches on this package prefix at runtime.
    "Lcom/swmansion/rnscreens/ScreenFragment;",
    "Lcom/swmansion/rnscreens/ScreenStackFragment;",
)
# PlatformColor names are constructed in JavaScript and resolved by Android's
# getIdentifier. DEX references alone retained only 9/31 colors in rc5, causing
# the signed Play build to crash while creating its first RCTView.
REQUIRED_COLOR_RESOURCES = {
    "lava_rn_" + name for name in (
        "safegreen", "safecontrolgreen", "softgreen", "panelactiongreen",
        "panelactionfill", "panelactionpressedfill", "quietcontrol", "lavaorange",
        "lavaorangesoft", "lavaorangetext", "lavaorangeselectedfill", "cream",
        "ink", "primarytext", "navigationforeground", "secondarytext", "tertiarytext",
        "groupedbackground", "cardbackground", "panelbackground", "panelstroke",
        "guardiansleepgray", "guardianfacelight", "dangerred", "errortext",
        "confirmationbuttontint", "actionforeground", "pressedsurface",
        "disabledsurface", "separator", "focusring",
    )
}
# Frozen iOS approval and its packet-preserving Android Ogg package. These are
# trusted-controller pins, independent of the candidate AAB and optional source
# paths. Updating a narration package requires an explicit reviewed pin change.
NARRATION_PREFIX = "base/assets/ExploreNarration/"
NARRATION_SOURCE_SHA = "3bd86116f0d12595965df03cf634066d393b4896"
NARRATION_SOURCE_MANIFEST_SHA256 = "e668885aa69245725d8c6ec3b458efd9b1a7fc792e910161d62e6882c920ffe3"
NARRATION_PACKAGE_MANIFEST_SHA256 = "5e2aa42dee2de07607e597336b4f7edfc4d087a0da1fad3c3c2b14f13e8a63e0"
NARRATION_CLIP_COUNT = 130


def verify_narration(archive: zipfile.ZipFile, entries: list[str]) -> None:
    manifests = (
        ("source-manifest.json", NARRATION_SOURCE_MANIFEST_SHA256),
        ("manifest.json", NARRATION_PACKAGE_MANIFEST_SHA256),
    )
    approved = {}
    for name, digest in manifests:
        entry = NARRATION_PREFIX + name
        if entry not in entries:
            raise ValueError(f"bundle is missing its approved narration {name}")
        data = archive.read(entry)
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f"packaged narration {name} differs from the approved manifest")
        approved[name] = json.loads(data)
    source = approved["source-manifest.json"]
    package = approved["manifest.json"]
    if (
        source.get("schemaVersion") != 1
        or source.get("listeningQualified") is not True
        or package.get("schemaVersion") != 1
        or package.get("sourceGitSHA") != NARRATION_SOURCE_SHA
        or package.get("sourceManifestSHA256") != NARRATION_SOURCE_MANIFEST_SHA256
        or package.get("sourceListeningQualified") is not True
        or package.get("androidListeningQualified") is not False
        or package.get("conversion") != "caf-opus-packet-preserving-ogg-v1"
    ):
        raise ValueError("packaged narration has an invalid approved source identity")
    clips = package.get("clips")
    if not isinstance(clips, list) or len(clips) != NARRATION_CLIP_COUNT:
        raise ValueError("packaged narration has an invalid approved clip count")
    expected = {NARRATION_PREFIX + name for name, _ in manifests}
    for clip in clips:
        name = clip.get("file", "")
        digest = clip.get("sha256", "")
        entry = NARRATION_PREFIX + name
        if (
            not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,100}\.ogg", name)
            or not re.fullmatch(r"[0-9a-f]{64}", digest)
            or entry in expected
            or clip.get("approved") is not True
            or clip.get("codec") != "opus"
            or clip.get("container") != "ogg"
        ):
            raise ValueError("packaged narration has an invalid approved clip identity")
        expected.add(entry)
        if entry not in entries:
            raise ValueError(f"bundle is missing its approved narration clip {name}")
        data = archive.read(entry)
        if not data.startswith(b"OggS") or hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f"packaged narration clip {name} differs from the approved audio")
    actual = {entry for entry in entries if entry.startswith(NARRATION_PREFIX) and not entry.endswith("/")}
    if actual != expected:
        raise ValueError("bundle contains unexpected narration assets")


def dex_strings(data: bytes) -> set[str]:
    """Return every string in a DEX string table (enough to prove name presence)."""
    if len(data) < 112 or not data.startswith(b"dex\n") or data[7] != 0:
        raise ValueError("expected a DEX file")
    string_ids_size, string_ids_off = struct.unpack_from("<II", data, 56)
    if string_ids_size == 0 or string_ids_off == 0:
        raise ValueError("DEX has no string table")
    if string_ids_off + 4 * string_ids_size > len(data):
        raise ValueError("DEX string table is truncated")
    strings: set[str] = set()
    for index in range(string_ids_size):
        offset = struct.unpack_from("<I", data, string_ids_off + 4 * index)[0]
        if offset >= len(data):
            raise ValueError("DEX string offset is out of range")
        cursor = offset
        while cursor < len(data) and data[cursor] & 0x80:  # skip the uleb128 length
            cursor += 1
        cursor += 1
        end = data.find(b"\x00", cursor)
        if cursor >= len(data) or end < 0:
            raise ValueError("DEX string data is truncated")
        strings.add(data[cursor:end].decode("utf-8", "replace"))
    return strings


def verify_native_registration(archive: zipfile.ZipFile, entries: list[str]) -> None:
    dex_entries = [entry for entry in entries if DEX_ENTRY.fullmatch(entry)]
    if not dex_entries:
        raise ValueError("bundle is missing its packaged DEX (base/dex/classes*.dex)")
    strings: set[str] = set()
    for entry in dex_entries:
        strings |= dex_strings(archive.read(entry))
    missing = [name for name in REQUIRED_REGISTRATIONS if name not in strings]
    if missing:
        raise ValueError(
            "packaged DEX is missing React Native registration after shrinking: "
            + ", ".join(missing),
        )


def proto_fields(data: bytes):
    """Read protobuf wire fields without adding a release-tool dependency."""
    cursor = 0

    def varint():
        nonlocal cursor
        result = 0
        for shift in range(0, 70, 7):
            if cursor >= len(data):
                raise ValueError("truncated resource-table protobuf")
            value = data[cursor]
            cursor += 1
            result |= (value & 0x7f) << shift
            if not value & 0x80:
                return result
        raise ValueError("invalid resource-table protobuf varint")

    while cursor < len(data):
        tag = varint()
        number, wire = tag >> 3, tag & 7
        if not number:
            raise ValueError("invalid resource-table protobuf field")
        if wire == 0:
            value = varint()
        elif wire in (1, 2, 5):
            size = varint() if wire == 2 else (8 if wire == 1 else 4)
            end = cursor + size
            if end > len(data):
                raise ValueError("truncated resource-table protobuf field")
            value = data[cursor:end]
            cursor = end
        else:
            raise ValueError("unsupported resource-table protobuf wire type")
        yield number, wire, value


def proto_messages(data: bytes, field: int) -> list[bytes]:
    return [value for number, wire, value in proto_fields(data) if number == field and wire == 2]


def verify_platform_colors(data: bytes, *, edition: str = "play") -> None:
    # aapt2 Resources.proto: ResourceTable.package=2, Package.name=2/type=3,
    # Type.name=2/entry=3, Entry.name=2/config_value=6, ConfigValue.value=2.
    # Parse actual definitions, rather than matching names in a source/debug
    # string pool or a JS bundle that can survive after resources are removed.
    # https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/Resources.proto
    # The caller selects the edition being built. Never combine definitions from
    # other packages, or infer the expected package from the candidate itself.
    if edition not in EDITION_PACKAGES:
        raise ValueError(f"unsupported Android edition: {edition}")
    expected_package = EDITION_PACKAGES[edition]
    colors: set[str] = set()
    for package in proto_messages(data, 2):
        if proto_messages(package, 2) != [expected_package]:
            continue
        for resource_type in proto_messages(package, 3):
            if proto_messages(resource_type, 2) != [b"color"]:
                continue
            for entry in proto_messages(resource_type, 3):
                names = proto_messages(entry, 2)
                values = proto_messages(entry, 6)
                if len(names) == 1 and any(proto_messages(value, 2) for value in values):
                    colors.add(names[0].decode("utf-8"))
    missing = sorted(REQUIRED_COLOR_RESOURCES - colors)
    if missing:
        raise ValueError("packaged resources are missing PlatformColor definitions after shrinking: " + ", ".join(missing))


def verify_elf(data: bytes, abi: str) -> None:
    elf_class, machine = ABIS[abi]
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4:7] != bytes((elf_class, 1, 1)):
        raise ValueError(f"{abi}: expected a little-endian ELF shared library")
    if struct.unpack_from("<HH", data, 16) != (3, machine):
        raise ValueError(f"{abi}: ELF type or machine does not match the ABI")
    if elf_class == 2:
        offset = struct.unpack_from("<Q", data, 32)[0]
        size, count = struct.unpack_from("<HH", data, 54)
        format_ = "<IIQQQQQQ"
    else:
        offset = struct.unpack_from("<I", data, 28)[0]
        size, count = struct.unpack_from("<HH", data, 42)
        format_ = "<IIIIIIII"
    if size != struct.calcsize(format_) or not count or offset + size * count > len(data):
        raise ValueError(f"{abi}: invalid ELF program headers")
    loads = 0
    for index in range(count):
        header = struct.unpack_from(format_, data, offset + size * index)
        if header[0] != 1:  # PT_LOAD
            continue
        loads += 1
        alignment = header[-1]
        if alignment < 16384 or alignment & (alignment - 1):
            raise ValueError(f"{abi}: LOAD alignment is not compatible with 16 KB pages")
    if not loads:
        raise ValueError(f"{abi}: shared library has no LOAD segment")


def verify_bundle(
    bundle: Path, jni_root: Path | None = None, js_bundle: Path | None = None,
    *, edition: str = "play",
) -> None:
    with zipfile.ZipFile(bundle) as archive:
        entries = archive.namelist()
        if len(set(entries)) != len(entries):
            raise ValueError("bundle has ambiguous duplicate entries")
        if JS_ASSET not in entries or not archive.getinfo(JS_ASSET).file_size:
            raise ValueError("bundle is missing its React Native JS asset")
        if JS_SOURCE_MAP_ASSET in entries:
            raise ValueError("bundle must not package a React Native JS source map")
        js = archive.read(JS_ASSET)
        if js_bundle is not None and js != js_bundle.read_bytes():
            raise ValueError("packaged React Native JS differs from the fresh bundle")
        verify_native_registration(archive, entries)
        if "base/resources.pb" not in entries:
            raise ValueError("bundle is missing its packaged resource table")
        verify_platform_colors(archive.read("base/resources.pb"), edition=edition)
        verify_narration(archive, entries)
        for abi in ABIS:
            for library, description in LIBRARIES.items():
                entry = f"base/lib/{abi}/{library}"
                if entry not in entries:
                    raise ValueError(f"bundle is missing the {abi} {description}")
                data = archive.read(entry)
                try:
                    verify_elf(data, abi)
                except ValueError as error:
                    raise ValueError(f"{library}: {error}") from error
                if jni_root is not None and data != (jni_root / abi / library).read_bytes():
                    raise ValueError(f"packaged {abi}/{library} differs from the freshly built library")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--jni-root", type=Path)
    parser.add_argument("--js-bundle", type=Path)
    parser.add_argument("--edition", choices=EDITION_PACKAGES, default="play",
                        help="expected release edition (default: play)")
    args = parser.parse_args()
    try:
        verify_bundle(args.bundle, args.jni_root, args.js_bundle, edition=args.edition)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Android runtime packaging failed: {error}\n")
    print(
        "Android bundle contains JS and all required native engines for four ABIs "
        "with 16 KB LOAD alignment, required RN names and PlatformColor resources, and all 130 "
        "narration clips matching the approved manifests",
    )
