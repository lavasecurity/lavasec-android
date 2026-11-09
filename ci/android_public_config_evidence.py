#!/usr/bin/env python3
"""Retain effective public Android build coordinates without retaining credentials."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit

SOURCE_CONFIG = Path("android/service-config.properties")
GENERATED_BASE = Path("android/app/build/generated/source/buildConfig")
NAMESPACE_PATH = Path("com/lavasec/app/BuildConfig.java")
MAX_INPUT_BYTES = 64 * 1024
MAX_EVIDENCE_BYTES = 16 * 1024
FIELD_TYPES = {
    "DEBUG": "boolean", "APPLICATION_ID": "String", "BUILD_TYPE": "String",
    "VERSION_CODE": "int", "VERSION_NAME": "String",
    "PLAY_CLOUD_PROJECT_NUMBER": "long", "GOOGLE_SERVER_CLIENT_ID": "String",
    "APPLE_BROWSER_OAUTH_ENABLED": "boolean", "BACKEND_URL": "String",
    "SUPABASE_URL": "String", "BACKUP_PASSKEY_RP_ORIGIN": "String",
    "SUPABASE_PUBLISHABLE_KEY": "String",
}
# AGP does not generate SDK fields by default. Never infer them from defaults;
# the packaged manifest verifier remains authoritative when these are absent.
OPTIONAL_FIELD_TYPES = {"MIN_SDK": "int", "TARGET_SDK": "int", "IS_FOSS": "boolean", "FLAVOR": "String"}
VARIANTS = ("debug", "release", "playDebug", "playRelease", "fossDebug", "fossRelease")
JAVA_STRING = r'"(?:\\.|[^"\\\r\n])*"'
COMMENT_OR_STRING = re.compile(JAVA_STRING + r'|//[^\r\n]*|/\*[\s\S]*?\*/')
DECLARATION = re.compile(
    r'\s*public\s+static\s+final\s+(String|boolean|int|long)\s+'
    r'([A-Z][A-Z0-9_]*)\s*=\s*(' + JAVA_STRING + r'|[^;"\r\n]+(?:' + JAVA_STRING + r'[^;"\r\n]*)?)\s*;'
)


class EvidenceError(ValueError):
    """A bounded diagnostic that never embeds input values."""


def read_bounded(path: Path) -> bytes:
    try:
        if not path.is_file() or path.stat().st_size > MAX_INPUT_BYTES:
            raise EvidenceError("Required public configuration input is absent or oversized")
        data = path.read_bytes()
    except OSError as error:
        raise EvidenceError("Required public configuration input is unreadable") from error
    if not data or len(data) > MAX_INPUT_BYTES:
        raise EvidenceError("Required public configuration input is empty or oversized")
    return data


def parse_generated(data: bytes, variant: str) -> dict:
    try:
        source = data.decode("utf-8")
    except UnicodeError as error:
        raise EvidenceError(f"{variant}: generated BuildConfig is not UTF-8") from error
    clean = COMMENT_OR_STRING.sub(
        lambda match: match.group() if match.group().startswith('"') else "\n" * match.group().count("\n"), source,
    )
    structure = re.fullmatch(
        r'\s*package\s+com\.lavasec\.app\s*;\s*public\s+final\s+class\s+BuildConfig\s*\{(.*)\}\s*',
        clean, re.DOTALL,
    )
    if not structure:
        raise EvidenceError(f"{variant}: invalid generated BuildConfig structure")
    body = structure.group(1)
    found: dict[str, tuple[str, str]] = {}
    position = 0
    while body[position:].strip():
        match = DECLARATION.match(body, position)
        if not match:
            raise EvidenceError(f"{variant}: malformed generated field declaration")
        field_type, name, expression = match.groups()
        if name in found:
            raise EvidenceError(f"{variant}: duplicate generated field declaration")
        found[name] = (field_type, expression.strip())
        position = match.end()
    result = {}
    for name, expected_type in {**FIELD_TYPES, **OPTIONAL_FIELD_TYPES}.items():
        if name not in found:
            if name in OPTIONAL_FIELD_TYPES:
                continue
            raise EvidenceError(f"{variant}: missing {name}")
        field_type, expression = found[name]
        if field_type != expected_type:
            raise EvidenceError(f"{variant}: invalid type for {name}")
        try:
            if field_type == "String":
                # Gradle's JsonOutput produces JSON-compatible Java literals.
                # Arbitrary Java expressions are never executed or interpreted.
                value = json.loads(expression)
                if not isinstance(value, str) or len(value) > 4096 or not value.isascii() or any(ord(c) < 32 or ord(c) == 127 for c in value):
                    raise ValueError()
            elif field_type == "boolean":
                if name == "DEBUG":
                    parsed = re.fullmatch(r'Boolean\.parseBoolean\("(true|false)"\)', expression)
                    if parsed:
                        expression = parsed.group(1)
                if expression not in ("true", "false"):
                    raise ValueError()
                value = expression == "true"
            else:
                if not re.fullmatch(r'(?:0|[1-9][0-9]*)[Ll]?' if field_type == "long" else r'(?:0|[1-9][0-9]*)', expression):
                    raise ValueError()
                value = int(expression.rstrip("Ll"))
                if not 0 <= value < (2 ** 63 if field_type == "long" else 2 ** 31):
                    raise ValueError()
        except (ValueError, TypeError, json.JSONDecodeError) as error:
            raise EvidenceError(f"{variant}: malformed {name}") from error
        result[name] = value
    return result


def public_key_receipt(value: str, variant: str) -> dict:
    key_format = None
    if re.fullmatch(r'sb_publishable_[A-Za-z0-9_-]+', value):
        key_format = "publishable"
    else:
        segments = value.split(".")
        if len(segments) == 3 and all(re.fullmatch(r'[A-Za-z0-9_-]+', part) for part in segments):
            try:
                payload = base64.b64decode(segments[1] + "=" * (-len(segments[1]) % 4), altchars=b"-_", validate=True)
                claims = json.loads(payload)
                if isinstance(claims, dict) and claims.get("role") == "anon":
                    key_format = "legacy_anon_jwt"
            except (ValueError, UnicodeError):
                pass
    if key_format is None:
        raise EvidenceError(f"{variant}: SUPABASE_PUBLISHABLE_KEY is not a public key")
    return {"sha256": hashlib.sha256(value.encode("utf-8")).hexdigest(), "validated_public": True, "format": key_format}


def validate_origin(value: str, field: str, variant: str, *, blank_allowed: bool = False) -> None:
    if not value and blank_allowed:
        return
    try:
        url = urlsplit(value)
        valid = (url.scheme == "https" and bool(url.hostname) and url.username is None and url.password is None
                 and url.path in ("", "/") and not url.query and not url.fragment
                 and re.fullmatch(r'[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*', url.hostname) is not None
                 and (url.port is None or 1 <= url.port <= 65535))
    except ValueError:
        valid = False
    if not valid:
        raise EvidenceError(f"{variant}: invalid public origin for {field}")


def variant_receipt(root: Path, variant: str) -> dict:
    flavored = re.fullmatch(r"(play|foss)(Debug|Release)", variant)
    edition = flavored.group(1) if flavored else "play"
    build_type = flavored.group(2).lower() if flavored else variant
    relative = GENERATED_BASE / edition / build_type / NAMESPACE_PATH if flavored else GENERATED_BASE / variant / NAMESPACE_PATH
    data = read_bounded(root / relative)
    fields = parse_generated(data, variant)
    expected_id = "com.lavasec.app" + (".foss" if edition == "foss" else "") + (".debug" if build_type == "debug" else "")
    if fields["APPLICATION_ID"] != expected_id or fields["BUILD_TYPE"] != build_type or fields["DEBUG"] != (build_type == "debug"):
        raise EvidenceError(f"{variant}: generated variant identity mismatch")
    if fields["VERSION_CODE"] < 1 or not re.fullmatch(r'[A-Za-z0-9.+_-]{1,128}', fields["VERSION_NAME"]):
        raise EvidenceError(f"{variant}: invalid generated version metadata")
    if flavored and (fields.get("IS_FOSS") != (edition == "foss") or fields.get("FLAVOR") != edition):
        raise EvidenceError(f"{variant}: generated edition identity mismatch")
    if edition == "foss":
        # A successful FOSS build must prove absence even when Play public
        # configuration is populated in the same source checkout/environment.
        if fields["PLAY_CLOUD_PROJECT_NUMBER"] != 0 or fields["APPLE_BROWSER_OAUTH_ENABLED"] or any(fields[name] for name in ("GOOGLE_SERVER_CLIENT_ID", "BACKEND_URL", "SUPABASE_URL", "SUPABASE_PUBLISHABLE_KEY", "BACKUP_PASSKEY_RP_ORIGIN")):
            raise EvidenceError(f"{variant}: FOSS contains excluded application-service coordinates")
    else:
        if fields["PLAY_CLOUD_PROJECT_NUMBER"] < 1 or not re.fullmatch(r'[0-9]+-[A-Za-z0-9_-]+\.apps\.googleusercontent\.com', fields["GOOGLE_SERVER_CLIENT_ID"]):
            raise EvidenceError(f"{variant}: invalid public Google coordinates")
        for name in ("BACKEND_URL", "SUPABASE_URL", "BACKUP_PASSKEY_RP_ORIGIN"):
            validate_origin(fields[name], name, variant, blank_allowed=name == "BACKUP_PASSKEY_RP_ORIGIN")
        fields["SUPABASE_PUBLISHABLE_KEY"] = public_key_receipt(fields["SUPABASE_PUBLISHABLE_KEY"], variant)
    if build_type == "debug" and fields["BACKUP_PASSKEY_RP_ORIGIN"]:
        raise EvidenceError("debug: passkey RP association must remain disabled")
    if any(fields[name] < 1 for name in ("MIN_SDK", "TARGET_SDK") if name in fields):
        raise EvidenceError(f"{variant}: invalid optional SDK metadata")
    return {"build_config": {"path": relative.as_posix(), "sha256": hashlib.sha256(data).hexdigest()}, "effective": fields}


def actual_head(root: Path) -> str:
    try:
        result = subprocess.run(["git", "-C", str(root), "rev-parse", "--verify", "HEAD^{commit}"],
                                capture_output=True, timeout=10, check=False)
    except (OSError, subprocess.SubprocessError) as error:
        raise EvidenceError("Cannot identify the actual Git HEAD") from error
    sha = result.stdout.decode("ascii", errors="replace").strip()
    if result.returncode != 0 or not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise EvidenceError("Cannot identify the actual Git HEAD")
    return sha


def collect(root: Path, variants: list[str]) -> dict:
    if not variants or len(set(variants)) != len(variants) or any(variant not in VARIANTS for variant in variants):
        raise EvidenceError("Select each supported variant at most once")
    root = root.resolve()
    config = read_bounded(root / SOURCE_CONFIG)
    return {
        "schema": 1,
        "source_sha": actual_head(root),
        "source_service_config": {"path": SOURCE_CONFIG.as_posix(), "sha256": hashlib.sha256(config).hexdigest()},
        "variants": {variant: variant_receipt(root, variant) for variant in variants},
    }


def write_receipt(receipt: dict, output: Path) -> None:
    if output.suffix != ".json" or output.is_symlink() or not output.parent.is_dir():
        raise EvidenceError("Output must be a JSON file in an existing artifact directory")
    data = (json.dumps(receipt, sort_keys=True, indent=2) + "\n").encode("utf-8")
    if len(data) > MAX_EVIDENCE_BYTES:
        raise EvidenceError("Public evidence exceeds its size bound")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=output.parent, prefix=".android-public-config-", delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(data)
        temporary.replace(output)
    except OSError as error:
        raise EvidenceError("Cannot write public configuration evidence") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--variants", required=True, nargs="+", choices=VARIANTS)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        write_receipt(collect(args.root, args.variants), args.output)
    except EvidenceError as error:
        print(f"Public configuration evidence refused: {error}", file=sys.stderr)
        return 1
    print("Public configuration evidence written.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
