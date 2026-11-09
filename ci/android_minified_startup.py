#!/usr/bin/env python3
"""Exercise the real release UI over adb; a successful am start is insufficient.

This is a regression gate for optimized startup, not full user qualification.
It uses ordinary launches and taps, never application test hooks or seeded state.
"""

import argparse
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


def visible_nodes(xml: str, package: str) -> list[dict[str, str]]:
    nodes = [node.attrib for node in ET.fromstring(xml).iter("node")
             if node.get("package") == package]
    if not nodes:
        raise ValueError("the foreground hierarchy does not contain the tested app")
    return nodes


def labels(nodes: list[dict[str, str]]) -> set[str]:
    return {node.get(key, "") for node in nodes for key in ("text", "content-desc")}


def tap_point(nodes: list[dict[str, str]], label: str) -> tuple[int, int]:
    # Headings can share a label with a tab. Prefer an actual clickable target.
    for node in sorted(nodes, key=lambda item: item.get("clickable") != "true"):
        if label.casefold() not in (node.get("text", "").casefold(), node.get("content-desc", "").casefold()) or node.get("enabled") != "true":
            continue
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if bounds:
            x1, y1, x2, y2 = map(int, bounds.groups())
            if x2 > x1 and y2 > y1:
                return (x1 + x2) // 2, (y1 + y2) // 2
    raise ValueError(f"no enabled, rendered target for {label!r}")


def capture_evidence(evidence: Path, adb, uid: str, started: str) -> None:
    """Best-effort diagnostics must neither hide a launch failure nor invent one."""
    errors = {}
    for name, command, binary in (
        ("crash.log", ("logcat", "--uid=" + uid, "-b", "crash", "-d", "-T", started, "-v", "threadtime"), False),
        ("last-screen.png", ("exec-out", "screencap", "-p"), True),
    ):
        try:
            content = adb(*command, binary=binary)
            if binary:
                (evidence / name).write_bytes(content)
            else:
                (evidence / name).write_text(content)
        except Exception as error:
            errors[name] = str(error)
    if errors:
        # Keep independent capture errors alongside the original pass/fail receipt.
        try:
            (evidence / "capture-errors.json").write_text(json.dumps(errors, indent=2) + "\n")
        except OSError as error:
            print(f"Could not retain capture errors: {error}; {errors}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", choices=("com.lavasec.app", "com.lavasec.app.qa"), default="com.lavasec.app")
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    if not os.environ.get("ANDROID_SERIAL"):
        parser.error("ANDROID_SERIAL must identify the exact test device")
    args.evidence.mkdir(parents=True, exist_ok=True)
    adb_binary = os.environ.get("ADB", "adb")

    def adb(*command: str, binary: bool = False) -> bytes | str:
        result = subprocess.run([adb_binary, *command], capture_output=True, timeout=25, check=True)
        return result.stdout if binary else result.stdout.decode("utf-8", "replace")

    package = args.package
    installed = adb("shell", "cmd", "package", "list", "packages", "-U", package)
    match = re.search(r"package:" + re.escape(package) + r" uid:(\d+)\b", installed)
    if not match:
        raise ValueError("the tested package is not installed")
    uid = match.group(1)
    started = adb("shell", "date", "+%s").strip() + ".000"
    component = adb("shell", "cmd", "package", "resolve-activity", "--brief", package).strip().splitlines()[-1]
    if not component.startswith(package + "/"):
        raise ValueError("the package has no matching launcher activity")
    observed = []

    def capture(name: str) -> list[dict[str, str]]:
        # A failed dump must never borrow a hierarchy from the previous screen.
        adb("shell", "rm", "-f", "/data/local/tmp/lava-minified-startup.xml")
        adb("shell", "uiautomator", "dump", "/data/local/tmp/lava-minified-startup.xml")
        xml = adb("shell", "cat", "/data/local/tmp/lava-minified-startup.xml")
        (args.evidence / (name + ".xml")).write_text(xml)
        (args.evidence / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p", binary=True))
        return visible_nodes(xml, package)

    try:
        for attempt in range(3):
            adb("shell", "am", "force-stop", package)
            launch = adb("shell", "am", "start", "-W", "-n", component)
            (args.evidence / f"launch-{attempt}.txt").write_text(launch)
            deadline = time.monotonic() + 60
            while True:
                # Unlike am start's result, a surviving process is necessary.
                adb("shell", "pidof", package)
                try:
                    nodes = capture(f"entry-{attempt}")
                    text = labels(nodes)
                    normalized = {value.casefold() for value in text}
                    if {"the internet is lava", "meet lava"} <= normalized:
                        x, y = tap_point(nodes, "Meet Lava")
                        adb("shell", "input", "tap", str(x), str(y))
                        page = labels(capture(f"interaction-{attempt}"))
                        if not {"lava stands guard here", "set up protection"} <= {value.casefold() for value in page}:
                            raise ValueError("the rendered welcome action did not advance onboarding")
                        observed.append("welcome -> protection introduction")
                        break
                    if {"lava stands guard here", "set up protection"} <= normalized:
                        observed.append("retained protection introduction")
                        break
                    if {"Guard", "Settings"} <= text:
                        x, y = tap_point(nodes, "Settings")
                        adb("shell", "input", "tap", str(x), str(y))
                        if "account & backup" not in {value.casefold() for value in labels(capture(f"interaction-{attempt}"))}:
                            raise ValueError("the rendered Settings action did not open Settings")
                        observed.append("Guard -> Settings")
                        break
                except (ValueError, ET.ParseError, subprocess.CalledProcessError):
                    if time.monotonic() >= deadline:
                        raise
                if time.monotonic() >= deadline:
                    raise ValueError("no interactive production entry appeared within 60 seconds")
                time.sleep(1)
            # Native screens/Compose continue mounting after the initial text.
            time.sleep(3)
            adb("shell", "pidof", package)
            crashes = adb("logcat", "--uid=" + uid, "-b", "crash", "-d", "-T", started, "-v", "threadtime")
            if "FATAL EXCEPTION" in crashes or "Fatal signal" in crashes:
                raise ValueError("the optimized app recorded a fatal crash")
        (args.evidence / "result.json").write_text(json.dumps({
            "status": "pass", "package": package, "uid": int(uid),
            "coldLaunches": 3, "observed": observed,
        }, indent=2) + "\n")
    except Exception as error:
        (args.evidence / "result.json").write_text(json.dumps({
            "status": "fail", "package": package, "uid": int(uid),
            "error": str(error), "observed": observed,
        }, indent=2) + "\n")
        raise
    finally:
        capture_evidence(args.evidence, adb, uid, started)


if __name__ == "__main__":
    main()
