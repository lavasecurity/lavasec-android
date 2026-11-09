"""Canonical Android release policy helpers used by CI source checks."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
POLICY_PATH = ROOT / "release" / "release-policy.json"
PLAY_VERSION_CODE_LIMIT = 2_100_000_000
CHANNELS = ("internal", "external")


def load_policy() -> dict[str, Any]:
    return json.loads(POLICY_PATH.read_text(encoding="utf-8"))


def release_defaults() -> tuple[str, int]:
    """Validate the single source shared by Gradle, preflight and source export."""
    properties = {}
    for raw in (ROOT / "release/android-version.properties").read_text(encoding="utf-8").splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        key, separator, value = raw.partition("=")
        if not separator or key in properties or key not in {"versionName", "versionCode"}:
            raise ValueError("invalid canonical Android release properties")
        properties[key] = value
    name = properties.get("versionName", "")
    code = properties.get("versionCode", "")
    if not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", name) or not re.fullmatch(r"[1-9][0-9]*", code):
        raise ValueError("invalid canonical Android release coordinates")
    if int(code) != version_code("external", "v" + name):
        raise ValueError("default code must use the stable ordinal for the marketing version")
    return name, int(code)


def _channel_policy(channel: str) -> dict[str, Any]:
    if channel not in CHANNELS:
        raise ValueError(f"unsupported release channel: {channel}")
    return load_policy()[channel]


def validate_tag(channel: str, tag: str) -> bool:
    pattern = _channel_policy(channel)["tagPattern"]
    return re.fullmatch(pattern, tag) is not None


def derive_release(channel: str) -> dict[str, str]:
    policy = load_policy()
    channel_policy = _channel_policy(channel)
    return {
        "repository": f"{policy['owner']}/{channel_policy['repository']}",
        "track": channel_policy["track"],
        "packageName": policy["packageName"],
    }


def version_code(channel: str, tag: str) -> int:
    """Shared package namespace: RCs sort before their stable release on every track."""
    if not validate_tag(channel, tag):
        raise ValueError("invalid release tag")
    components = tag[1:].split("-rc")
    major, minor, patch = (int(part) for part in components[0].split("."))
    ordinal = int(components[1]) if channel == "internal" else 9_999
    if minor > 99 or patch > 99 or (channel == "internal" and ordinal > 9_998):
        raise ValueError("version component exceeds its reserved range")
    result = major * 100_000_000 + minor * 1_000_000 + patch * 10_000 + ordinal
    if not 1 <= result <= PLAY_VERSION_CODE_LIMIT:
        raise ValueError("derived version code exceeds the Google Play limit")
    return result
