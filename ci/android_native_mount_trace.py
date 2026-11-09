#!/usr/bin/env python3
"""Project at most eight native Fabric mount facts; never retain raw native logs."""
import argparse
from collections import deque
from datetime import datetime
import json
import os
from pathlib import Path
import re
import sys

LIMIT = 8
# Two eight-row fixed-vocabulary ancestor chains + bounded scalar framing need
# more than 1024 bytes. The maximal legitimate producer payload is tested <3072;
# 4096 also accommodates its threadtime header/prefix without unbounded reads.
MAX_LINE_BYTES = 4096
MAX_ANCESTORS = 8
MAX_INPUT_BYTES = 1024 * 1024
INT_MAX = 2147483647
# Native class simple names only. All other names become the fixed token `other`.
CLASSES = frozenset((
    "ReactViewGroup", "ReactScrollView", "ReactHorizontalScrollView", "ReactTextView",
    "Screen", "ScreenStack", "ScreenContentWrapper", "ScreensCoordinatorLayout",
    "ScreenStackHeaderConfig", "ScreenStackHeaderSubview", "Toolbar", "ReactRootView", "ReactSurfaceView",
    "View", "ViewGroup", "FrameLayout", "LinearLayout", "CoordinatorLayout",
    "ComposeView", "AndroidComposeView", "LavaTextFieldView", "LavaDecorationView",
    "LavaNativePageView", "LavaShareCardSurfaceView", "LavaShareQrView",
))
MARKERS = frozenset((
    "screen.scroll", "sheet.pinned-header", "sheet.native-footer", "plus.context",
    "plus.thank-you.mascot", "plus.thank-you.mascot.gesture", "lava-route-privacy-cover",
    "foreground-flow-privacy-cover", "vpn-editor-privacy-cover",
))
WITNESS_PREFIX = "ParentWitness "
STATUSES = frozenset((
    "resolved", "off-main", "no-context", "context-changed", "wrong-host", "inactive-context",
    "missing-view", "identity-mismatch", "wrong-window", "non-view-parent", "observer-error",
))
WITNESS_FIELDS = frozenset((
    "schema", "child", "attemptedParent", "previousParent", "mainThread", "currentContext",
    "sameWindow", "resolved", "status", "childChain", "attemptedParentChain",
    "childTruncated", "attemptedParentTruncated",
))
NODE_FIELDS = frozenset(("tag", "class", "marker", "parentIndex"))
HEADER = re.compile(
    r"[ ]*(?P<month>[0-9]{2})-(?P<day>[0-9]{2}) "
    r"(?P<hour>[0-9]{2}):(?P<minute>[0-9]{2}):(?P<second>[0-9]{2})\.(?P<milli>[0-9]{3})"
    r" +(?P<pid>[0-9]{1,10}) +(?P<tid>[0-9]{1,10}) +E "
    r"(?P<tag>unknown:SurfaceMountingManager|SurfaceMountingManager|AndroidRuntime|LavaFabricMountWitness): "
    r"(?P<message>[^\r\n]+)"
)
SIMPLE_CLASS = r"[A-Za-z_$][A-Za-z0-9_$]{0,79}"
ALREADY_PARENT = re.compile(
    r"java\.lang\.IllegalStateException: addViewAt: cannot insert view \[(?P<child>[0-9]{1,10})\] "
    r"into parent \[(?P<attempted>[0-9]{1,10})\]: View already has a parent: "
    r"\[(?P<previous>-?[0-9]{1,10})\] Parent: (?P<parent_class>" + SIMPLE_CLASS + r") "
    r"View: (?P<child_class>" + SIMPLE_CLASS + r")"
)
INSERT_FAILED = re.compile(
    r"java\.lang\.IllegalStateException: addViewAt: failed to insert view \[(?P<child>[0-9]{1,10})\] "
    r"into parent \[(?P<attempted>[0-9]{1,10})\] at index (?P<index>[0-9]{1,10})"
)


class InputRefused(ValueError):
    pass



def strict_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("Duplicate native witness field")
        value[key] = item
    return value


def integer(value, minimum=-1):
    return type(value) is int and minimum <= value <= INT_MAX


def project_chain(value):
    if not isinstance(value, list) or len(value) > MAX_ANCESTORS:
        raise ValueError("Invalid native witness chain")
    result = []
    for node in value:
        if not isinstance(node, dict) or set(node) != NODE_FIELDS:
            raise ValueError("Invalid native witness node")
        if not integer(node["tag"]) or not integer(node["parentIndex"]):
            raise ValueError("Invalid native witness node range")
        native_class, marker = node["class"], node["marker"]
        if not isinstance(native_class, str) or re.fullmatch(SIMPLE_CLASS, native_class) is None:
            raise ValueError("Invalid native witness class")
        if not isinstance(marker, str) or len(marker) > 128 or not marker.isascii():
            raise ValueError("Invalid native witness marker")
        result.append({"tag": node["tag"], "parentIndex": node["parentIndex"],
                       "class": native_class if native_class in CLASSES else "other",
                       "marker": marker if marker in MARKERS else "other"})
    return result


def project_witness(message):
    if not message.startswith(WITNESS_PREFIX):
        return None
    encoded = message[len(WITNESS_PREFIX):]
    if not encoded.isascii() or len(encoded) > 3072:
        return None
    try:
        value = json.loads(encoded, object_pairs_hook=strict_object,
                           parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Invalid constant")))
        if not isinstance(value, dict) or set(value) != WITNESS_FIELDS:
            return None
        if type(value["schema"]) is not int or value["schema"] != 1:
            return None
        if not integer(value["child"], 1) or not integer(value["attemptedParent"], 1) or not integer(value["previousParent"]):
            return None
        if any(type(value[key]) is not bool for key in ("mainThread", "resolved", "childTruncated", "attemptedParentTruncated")):
            return None
        if any(value[key] is not None and type(value[key]) is not bool for key in ("currentContext", "sameWindow")):
            return None
        if not isinstance(value["status"], str) or value["status"] not in STATUSES:
            return None
        child = project_chain(value["childChain"])
        attempted = project_chain(value["attemptedParentChain"])
        if value["resolved"]:
            if value["status"] != "resolved" or not value["mainThread"] or value["currentContext"] is not True or value["sameWindow"] is not True:
                return None
            if len(child) < 2 or not attempted or child[0]["tag"] != value["child"] or child[1]["tag"] != value["previousParent"] or attempted[0]["tag"] != value["attemptedParent"]:
                return None
            if value["childTruncated"] and len(child) != MAX_ANCESTORS or value["attemptedParentTruncated"] and len(attempted) != MAX_ANCESTORS:
                return None
        elif value["status"] == "resolved" or child or attempted or value["childTruncated"] or value["attemptedParentTruncated"]:
            return None
        if value["status"] == "off-main":
            if value["mainThread"] or value["currentContext"] is not None or value["sameWindow"] is not None:
                return None
        elif not value["mainThread"]:
            return None
        if value["status"] in ("no-context", "context-changed") and value["currentContext"] is not False:
            return None
        if value["status"] == "wrong-window" and (value["currentContext"] is not True or value["sameWindow"] is not False):
            return None
        return {"kind": "parent-witness", **{key: value[key] for key in WITNESS_FIELDS if key not in ("schema", "childChain", "attemptedParentChain")},
                "childChain": child, "attemptedParentChain": attempted}
    except (ValueError, TypeError, RecursionError, OverflowError):
        return None


def project_line(raw):
    """Only complete threadtime lines with exact tag/message framing are admitted."""
    if len(raw) > MAX_LINE_BYTES or not raw.endswith(b"\n"):
        return None
    try:
        line = raw[:-1].decode("utf-8", errors="strict")
    except UnicodeError:
        return None
    header = HEADER.fullmatch(line)
    if header is None:
        return None
    values = header.groupdict()
    try:
        # Threadtime has no year; a leap year admits every valid month/day pair.
        datetime(2000, *(int(values[key]) for key in ("month", "day", "hour", "minute", "second")))
    except ValueError:
        return None
    pid, tid = int(values["pid"]), int(values["tid"])
    if not 0 < pid <= INT_MAX or not 0 < tid <= INT_MAX:
        return None
    message = values["message"]
    if values["tag"] == "LavaFabricMountWitness":
        witness = project_witness(message)
        if witness is None:
            return None
        return {**witness, "pid": pid, "tid": tid,
                "millisOfDay": ((int(values["hour"]) * 60 + int(values["minute"])) * 60 + int(values["second"])) * 1000 + int(values["milli"])}
    if values["tag"] in ("unknown:SurfaceMountingManager", "SurfaceMountingManager"):
        match = ALREADY_PARENT.fullmatch(message)
        kind = "already-parent"
    else:
        match = INSERT_FAILED.fullmatch(message)
        kind = "insert-failed"
    if match is None:
        return None
    child, attempted = int(match["child"]), int(match["attempted"])
    if not 0 < child <= INT_MAX or not 0 < attempted <= INT_MAX:
        return None
    previous = int(match["previous"]) if kind == "already-parent" else None
    index = int(match["index"]) if kind == "insert-failed" else None
    if previous is not None and not -1 <= previous <= INT_MAX:
        return None
    if index is not None and not 0 <= index <= INT_MAX:
        return None
    known_class = lambda key: match[key] if match[key] in CLASSES else "other"
    return {
        "kind": kind, "pid": pid, "tid": tid,
        "millisOfDay": ((int(values["hour"]) * 60 + int(values["minute"])) * 60 + int(values["second"])) * 1000 + int(values["milli"]),
        "child": child, "attemptedParent": attempted, "previousParent": previous, "index": index,
        "previousParentClass": known_class("parent_class") if kind == "already-parent" else None,
        "childClass": known_class("child_class") if kind == "already-parent" else None,
    }


def retained_tail(stream):
    events = deque(maxlen=LIMIT)
    consumed = 0
    while True:
        raw = stream.readline(MAX_LINE_BYTES + 1)
        consumed += len(raw)
        if consumed > MAX_INPUT_BYTES:
            raise InputRefused("Native mount input exceeded its fixed budget")
        if not raw:
            break
        if len(raw) > MAX_LINE_BYTES or not raw.endswith(b"\n"):
            # Drain an oversized physical line without ever retaining its fragments.
            while raw and not raw.endswith(b"\n"):
                raw = stream.readline(MAX_LINE_BYTES + 1)
                consumed += len(raw)
                if consumed > MAX_INPUT_BYTES:
                    raise InputRefused("Native mount input exceeded its fixed budget")
            continue
        value = project_line(raw)
        if value is not None:
            events.append(value)
    return {"schema": 1, "events": list(events)}


def write_receipt(path, value):
    # Failure phases own separate immutable paths. Refuse overwrites and symlinks.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        output.write(json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n")
        output.flush()
        os.fsync(output.fileno())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        value = retained_tail(sys.stdin.buffer)
        write_receipt(args.output, value)
    except (InputRefused, OSError):
        print("Native mount diagnostic refused.", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
