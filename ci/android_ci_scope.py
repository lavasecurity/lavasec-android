#!/usr/bin/env python3
"""Classify the complete event diff; only known documentation/JS may skip native work."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

SHA = re.compile(r"(?:[0-9a-f]{40}|[0-9a-f]{64})\Z")
PUBLIC_REPOSITORY = "lavasecurity/lavasec-android"
ENGINE_INPUTS = (
    "ThirdParty/", "scripts/build-wireguard-core-android.sh",
    "scripts/build-doq-core-android.sh", "scripts/prepare-wireguard-core-android.sh",
    ".github/workflows/wireguard-core-android.yml",
)


def documentation(path):
    return path.startswith("docs/") or path.endswith(".md")


def javascript(path):
    # Specs, dependencies, codegen, Metro configuration and unknown helpers rebuild.
    # src/NativeLavaApp.ts is the runtime wrapper; specs/ owns the native ABI.
    if path in ("android/react-native/scripts/canonical-source.manifest.json",
                "android/react-native/tests/shared-ui-reference.json"):
        return True  # validated by canonical:check; these files do not generate native code
    return bool(re.fullmatch(
        r"android/react-native/(?:(?:src|canonical/(?:app|review|src|gallery))/.+\.(?:ts|tsx|js|jsx)|tests/.+\.test\.[jt]sx?)",
        path,
    ))


def classify(files, *, event, repository, force_full=False):
    unknown = files is None or not files
    docs_only = not unknown and all(documentation(p) for p in files)
    native = unknown or any(not documentation(p) and not javascript(p) for p in files)
    engine = unknown or any(p.startswith(ENGINE_INPUTS) for p in files)
    if force_full or event not in ("push", "pull_request"):
        docs_only, native, engine = False, True, True
    extended = event != "pull_request" or force_full
    return {
        "docs_only": docs_only,
        "native_required": native,
        "engine_required": engine,
        "full_build": native and (repository == PUBLIC_REPOSITORY or extended),
        # UI-only main changes still need real rendered/device qualification.
        "qualification_required": not docs_only and extended,
    }


def changed_files(event, base, head, *, cwd=None):
    if event not in ("push", "pull_request") or not SHA.fullmatch(base or "") or not SHA.fullmatch(head or ""):
        return None
    if set(base) == {"0"} or set(head) == {"0"}:
        return None
    result = subprocess.run(
        ["git", "diff", "--no-ext-diff", "--no-textconv", "--no-renames",
         "--name-only", "-z", f"{base}..{head}", "--"],
        cwd=cwd, capture_output=True,
    )
    if result.returncode:
        return None
    try:
        return [p for p in result.stdout.decode("utf-8").split("\0") if p]
    except UnicodeError:
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event", required=True)
    parser.add_argument("--base", default="")
    parser.add_argument("--head", default="")
    parser.add_argument("--repository", required=True)
    parser.add_argument("--force-full", action="store_true")
    args = parser.parse_args()
    result = classify(changed_files(args.event, args.base, args.head),
                      event=args.event, repository=args.repository, force_full=args.force_full)
    print(json.dumps(result, sort_keys=True))
    if output := os.environ.get("GITHUB_OUTPUT"):
        with Path(output).open("a") as stream:
            for key, value in result.items():
                stream.write(f"{key}={str(value).lower()}\n")


if __name__ == "__main__":
    main()
