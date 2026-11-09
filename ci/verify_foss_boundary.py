#!/usr/bin/env python3
"""Audit resolved FOSS dependencies, packaged manifest and release DEX together.

This is artifact/dependency evidence, not a claim about captured device traffic.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

try:
    from .verify_android_bundle import DEX_ENTRY, dex_strings
except ImportError:
    from verify_android_bundle import DEX_ENTRY, dex_strings

PROHIBITED_COORDINATES = (
    "com.android.billingclient:", "com.google.android.play:", "com.google.android.gms:",
    "com.google.android.libraries.identity.googleid:",
    "androidx.credentials:credentials-play-services-auth:",
)
PROHIBITED_DEX = (
    "Lcom/android/billingclient/", "Lcom/google/android/play/", "Lcom/google/android/gms/",
    "Lcom/google/android/libraries/identity/googleid/", "Landroidx/credentials/playservices/",
)
ANDROID = "{http://schemas.android.com/apk/res/android}"
MAX_REPORT_BYTES = 16 * 1024 * 1024
PACKAGED_MANIFEST = "base/manifest/AndroidManifest.xml"
BUNDLETOOL_SHA256 = "a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"


def verify_dependencies(data: bytes) -> None:
    if not data or len(data) > MAX_REPORT_BYTES:
        raise ValueError("FOSS dependency report is absent or oversized")
    report = data.decode("utf-8", errors="strict")
    header = re.search(r"(?m)^fossReleaseRuntimeClasspath - [^\r\n]+$", report)
    if header is None:
        raise ValueError("FOSS release runtime configuration was not resolved")
    graph = report[header.end():].split("A web-based, searchable dependency report", 1)[0]
    if not re.search(r"(?m)^[| ]*(?:\+---|\\---) ", graph) or " FAILED" in graph or "Could not resolve" in graph:
        raise ValueError("FOSS dependency graph is missing or unresolved")
    if any(marker in graph for marker in PROHIBITED_COORDINATES):
        raise ValueError("FOSS release resolves a prohibited Google or billing integration")


def verify_manifest(data: bytes) -> None:
    root = ET.fromstring(data)
    if root.tag != "manifest" or root.get("package") != "com.lavasec.app.foss":
        raise ValueError("FOSS release manifest has the wrong package identity")
    for element in root.iter():
        values = list(element.attrib.values())
        if any(".account.AppleOAuth" in value or "com.google.android.gms" in value or
               "com.google.android.play" in value or "androidx.credentials.playservices" in value
               for value in values):
            raise ValueError("FOSS manifest contains an excluded account/Google component")
        if element.tag == "data" and element.get(ANDROID + "scheme", "").endswith(".auth"):
            raise ValueError("FOSS manifest exposes an account callback")


def verify_bundle(bundle: Path) -> dict:
    if not bundle.is_file():
        raise ValueError("FOSS release bundle is missing")
    with zipfile.ZipFile(bundle) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("FOSS bundle contains duplicate entries")
        if PACKAGED_MANIFEST not in names or not archive.read(PACKAGED_MANIFEST):
            raise ValueError("FOSS bundle is missing its actual packaged manifest")
        dex = [name for name in names if DEX_ENTRY.fullmatch(name)]
        if not dex:
            raise ValueError("FOSS bundle has no release DEX")
        for name in dex:
            for value in dex_strings(archive.read(name)):
                if any(marker in value for marker in PROHIBITED_DEX):
                    raise ValueError("FOSS DEX contains an excluded Google or billing integration")
        return {name: hashlib.sha256(archive.read(name)).hexdigest() for name in dex}


def dump_packaged_manifest(bundle: Path, bundletool: Path) -> bytes:
    if not bundletool.is_file() or hashlib.sha256(bundletool.read_bytes()).hexdigest() != BUNDLETOOL_SHA256:
        raise ValueError("bundletool differs from the reviewed release-controller pin")
    result = subprocess.run(["java", "-jar", str(bundletool), "dump", "manifest", "--bundle=" + str(bundle)],
                            capture_output=True, check=False, timeout=60)
    if result.returncode != 0 or not result.stdout or len(result.stdout) > 1024 * 1024:
        raise ValueError("Cannot decode the actual packaged FOSS manifest")
    return result.stdout


def audit(bundle: Path, dependencies: Path, bundletool: Path) -> dict:
    graph = dependencies.read_bytes()
    verify_dependencies(graph)
    dex = verify_bundle(bundle)
    # Decode this exact AAB with a checksum-pinned tool. A merged sidecar manifest
    # cannot establish package/component identity for the artifact being audited.
    xml = dump_packaged_manifest(bundle, bundletool)
    verify_manifest(xml)
    return {"schemaVersion": 1, "edition": "foss", "packageName": "com.lavasec.app.foss",
            "bundleSha256": hashlib.sha256(bundle.read_bytes()).hexdigest(),
            "dependencyReportSha256": hashlib.sha256(graph).hexdigest(),
            "packagedManifestSha256": hashlib.sha256(xml).hexdigest(), "dex": dex}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--dependencies", required=True, type=Path)
    parser.add_argument("--bundletool", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    receipt = audit(args.bundle, args.dependencies, args.bundletool)
    args.output.write_text(json.dumps(receipt, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print("FOSS release dependency, manifest and DEX boundary verified")


if __name__ == "__main__":
    main()
