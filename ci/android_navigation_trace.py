#!/usr/bin/env python3
"""Retain only a bounded tail of public navigation counters; never raw JS logs."""
import argparse
import collections
import json
import os
import sys
from pathlib import Path

PREFIX = b"LavaNavigationRemovalTrace "
PRODUCER_PREFIX = b"LavaNavigationProducerTrace "
BOUNDARIES = ("guard-parent", "guard-routes", "settings-parent", "settings-routes")
NUMERIC = ("events", "commits", "setCalls", "setChanges", "additions", "removals",
           "notifyCalls", "forwardedNotifications", "nativeRouteCount")
BOOLEAN = ("publishedObjectChanged", "publishedContextChanged", "publishedRouteSetChanged", "nativeStateChanged")
OPTIONAL = ("ownedRegistrations", "ownedProtectedRoutes")
LIMIT = 256
PRODUCER_NUMERIC = ("events", "renders", "commits", "snapshotRevision", "hydrationEpoch",
                    "presentationSerial", "securityReadRevision", "commands", "presentationCommands",
                    "presentationReadyCommands", "authorizationCommands", "endTurnCommands",
                    "filterOpenCommands", "filterCloseCommands", "filterEditCommands", "filterSaveCommands",
                    "filterReviewCommands", "filterApplyCommands", "filterCancelCommands", "filterDomainCommands",
                    "queryCommands", "hapticCommands", "refreshCommands", "otherCommands")
PRODUCER_BOOLEAN = ("snapshotPresent", "hydrationRequired", "presentationAuthority", "securityReadRevisionPresent")
PRODUCER_STATUSES = ("none", "pending", "accepted", "refused")


def project(value):
    if not isinstance(value, dict) or not isinstance(value.get("boundary"), str) or value["boundary"] not in BOUNDARIES:
        return None
    if any(type(value.get(key)) is not int or not 0 <= value[key] <= 2147483647 for key in NUMERIC):
        return None
    if not 1 <= value["events"] <= LIMIT or any(type(value.get(key)) is not bool for key in BOOLEAN):
        return None
    if any(key in value and (type(value[key]) is not int or not 0 <= value[key] <= 2147483647) for key in OPTIONAL):
        return None
    return {"boundary": value["boundary"], **{key: value[key] for key in NUMERIC + BOOLEAN},
            **{key: value[key] for key in OPTIONAL if key in value}}


def project_producer(value):
    if not isinstance(value, dict):
        return None
    if any(type(value.get(key)) is not int or not 0 <= value[key] <= 2147483647 for key in PRODUCER_NUMERIC):
        return None
    if not 1 <= value["events"] <= LIMIT or any(type(value.get(key)) is not bool for key in PRODUCER_BOOLEAN):
        return None
    if type(value.get("presentationStatus")) is not str or value["presentationStatus"] not in PRODUCER_STATUSES:
        return None
    return {**{key: value[key] for key in PRODUCER_NUMERIC + PRODUCER_BOOLEAN},
            "presentationStatus": value["presentationStatus"]}


def retained_tail(stream):
    tails = {name: collections.deque(maxlen=LIMIT) for name in (*BOUNDARIES, "producer")}
    ordinal = 0
    while True:
        raw = stream.readline(8193)
        if not raw:
            break
        if len(raw) > 8192 or not raw.endswith(b"\n"):
            while raw and not raw.endswith(b"\n"):
                raw = stream.readline(8193)
            continue
        prefix = PREFIX if raw.startswith(PREFIX) else PRODUCER_PREFIX if raw.startswith(PRODUCER_PREFIX) else None
        if prefix is None:
            continue
        try:
            decoded = json.loads(raw[len(prefix):].decode("utf-8"))
            value = project(decoded) if prefix == PREFIX else project_producer(decoded)
        except (ValueError, UnicodeError, RecursionError):
            continue
        if value is not None:
            ordinal += 1
            tails[value["boundary"] if prefix == PREFIX else "producer"].append((ordinal, value))
    return [value for _, value in sorted(item for tail in tails.values() for item in tail)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    values = retained_tail(sys.stdin.buffer)
    # The calling lane chooses an existing private evidence directory.
    with args.output.open("w", encoding="utf-8") as output:
        os.chmod(args.output, 0o600)
        for value in values:
            prefix = PREFIX if "boundary" in value else PRODUCER_PREFIX
            output.write(prefix.decode("ascii") + json.dumps(value, separators=(",", ":")) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
