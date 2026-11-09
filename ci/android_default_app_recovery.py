#!/usr/bin/env python3
"""Plan a finite, failure-only queue of never-advertised default-app tests."""
import argparse
import hashlib
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

if __package__:
    from .android_emulator_evidence import REQUIRED_CASES
else:
    from android_emulator_evidence import REQUIRED_CASES

EXCLUDED = frozenset((
    "com.lavasec.app.reactnative.LavaReactProductJourneyTest",
    "com.lavasec.app.reactnative.LavaReactSharedForegroundOnboardingDeviceTest",
    "com.lavasec.app.reactnative.LavaReactExportedEntryJourneyTest",
    "com.lavasec.app.reactnative.VpnProcessDeathInstrumentationTest",
    "com.lavasec.app.reactnative.LavaReactTalkBackJourneyTest",
))
DEFAULT_CASES = tuple(f"{name}#{method}" for name, methods in REQUIRED_CASES.items()
                      if name not in EXCLUDED and not name.startswith("com.lavasec.app.vpn.") for method in methods)
DEFAULT_SET = frozenset(DEFAULT_CASES)
DEFAULT_CLASSES = frozenset(key.split("#", 1)[0] for key in DEFAULT_CASES)
MAX_DEFAULT_CASES = 73

# This exact additional @Test exists in the reviewed app APK sources but is
# not part of the 112-case qualification oracle. A complete advertisement only
# identifies itself; an absent or arbitrary unknown method remains ambiguous
# and still suppresses the entire required class.
KNOWN_NON_REQUIRED_CASES = frozenset((
    "com.lavasec.app.backup.BackupProductionJourneyInstrumentationTest#passkeyPrfSeamDecryptsAdoptsAndPreservesEveryPortableSlot",
))


class RecoveryError(Exception):
    pass


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def advertised(text):
    """Advertising a method is sufficient to exclude it, even before START/EOF."""
    seen, partial = set(), {}

    def finish(started=False):
        name, method = partial.get("class"), partial.get("test")
        if name in DEFAULT_CLASSES:
            key = f"{name}#{method}" if method else None
            if key in DEFAULT_SET:
                seen.add(key)
            elif key not in KNOWN_NON_REQUIRED_CASES:
                seen.update(key for key in DEFAULT_CASES if key.startswith(name + "#"))
        elif method and not name:
            matches = {key for key in DEFAULT_CASES if key.split("#", 1)[1] == method}
            seen.update(matches if matches else DEFAULT_SET)
        elif started and not name:
            seen.update(DEFAULT_SET)
        elif name and not re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+", name):
            raise RecoveryError("A raw advertised class is malformed")
        partial.clear()

    for line in text.splitlines():
        match = re.fullmatch(r"INSTRUMENTATION_STATUS: (class|test)=(.*)", line.rstrip("\r"))
        if match:
            field, value = match.groups()
            if field in partial:
                finish()
            partial[field] = value.strip()
            if "class" in partial and "test" in partial:
                key = partial["class"] + "#" + partial["test"]
                if key in DEFAULT_SET:
                    seen.add(key)
        code = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line.rstrip("\r"))
        if code:
            finish(started=code[1] == "1")
    finish()
    return seen


def xml_observed(results):
    seen = set()
    for path in sorted(results.rglob("*.xml")):
        try:
            tree = ET.parse(path)
        except (ET.ParseError, OSError) as error:
            raise RecoveryError("Current result XML is unreadable") from error
        for case in tree.iter("testcase"):
            name, method = case.get("classname"), case.get("name")
            if name in DEFAULT_CLASSES:
                key = f"{name}#{method}"
                if key in DEFAULT_SET:
                    seen.add(key)
                elif key not in KNOWN_NON_REQUIRED_CASES:
                    seen.update(key for key in DEFAULT_CASES if key.startswith(name + "#"))
    return seen


def observed(results, original):
    seen = xml_observed(results)
    for path in (original, *sorted(results.glob("recovery-*.txt"))):
        try:
            seen.update(advertised(path.read_text(encoding="utf-8")))
        except (OSError, UnicodeError) as error:
            raise RecoveryError("Current instrumentation transcript is unreadable") from error
    return seen


def write_json(path, value):
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8") as output:
        os.chmod(temporary, 0o600)
        json.dump(value, output, separators=(",", ":"))
        output.write("\n")
    temporary.replace(path)


def make_plan(results, original, original_xml):
    if len(DEFAULT_CASES) != MAX_DEFAULT_CASES or len(DEFAULT_SET) != MAX_DEFAULT_CASES:
        raise RecoveryError("The reviewed default-app inventory changed")
    if not original.is_file() or not original.stat().st_size or not original_xml.is_file():
        raise RecoveryError("Original raw default-app evidence is missing")
    tree = ET.parse(original_xml)
    if not any(case.find("failure") is not None or case.find("error") is not None for case in tree.iter("testcase")):
        # A failed adb command with a fully passed transcript cannot authorize a
        # new invocation merely because a required case was intentionally omitted.
        selectors = []
    else:
        seen = observed(results, original)
        selectors = [key for key in DEFAULT_CASES if key not in seen]
    return {"schema": 1, "original_txt_sha256": digest(original), "original_xml_sha256": digest(original_xml),
            "selectors": selectors}


def load_plan(path, original, original_xml):
    value = json.loads(path.read_text(encoding="utf-8"))
    selectors = value.get("selectors") if isinstance(value, dict) else None
    if not isinstance(value, dict) or value.get("schema") != 1 or not isinstance(selectors, list) or len(selectors) > MAX_DEFAULT_CASES:
        raise RecoveryError("Recovery plan is invalid")
    if any(not isinstance(key, str) or key not in DEFAULT_SET for key in selectors) or len(set(selectors)) != len(selectors):
        raise RecoveryError("Recovery plan contains a non-default selector")
    if value.get("original_txt_sha256") != digest(original) or value.get("original_xml_sha256") != digest(original_xml):
        raise RecoveryError("Original default-app evidence changed")
    return value


def claim(plan, original, original_xml, results, ledger, selector):
    value = load_plan(plan, original, original_xml)
    attempted = json.loads(ledger.read_text(encoding="utf-8")) if ledger.exists() else []
    if not isinstance(attempted, list) or any(not isinstance(key, str) or key not in value["selectors"] for key in attempted) or len(attempted) != len(set(attempted)):
        raise RecoveryError("Recovery attempt ledger is invalid")
    if selector not in value["selectors"] or selector in attempted or selector in observed(results, original):
        return False
    # Claim before invoking adb. A launch refusal or zero-progress crash is never
    # permission to retry this identity during the same original cohort.
    write_json(ledger, attempted + [selector])
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("plan", "selectors", "claim", "verify"))
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--original", type=Path, required=True)
    parser.add_argument("--original-xml", type=Path, required=True)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--ledger", type=Path)
    parser.add_argument("--selector")
    args = parser.parse_args()
    try:
        if args.mode == "plan":
            write_json(args.plan, make_plan(args.results, args.original, args.original_xml))
        elif args.mode == "claim":
            if args.ledger is None or args.selector is None:
                raise RecoveryError("Recovery claim arguments are missing")
            return 0 if claim(args.plan, args.original, args.original_xml, args.results, args.ledger, args.selector) else 2
        else:
            value = load_plan(args.plan, args.original, args.original_xml)
            if args.mode == "selectors":
                for selector in value["selectors"]:
                    print(selector)
    except (RecoveryError, OSError, ValueError, TypeError, ET.ParseError):
        print("Default-app recovery refused: current bounded evidence is not authoritative.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
