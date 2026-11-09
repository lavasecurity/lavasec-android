#!/usr/bin/env python3
"""Runnable FINAL readiness verdict for the Android candidate (infra #314 / plan 309 F25).

Ordinary preview CI runs ``test_android_rn_qualification`` and tolerates an incomplete ledger.
This is the separate, executable completion gate: every required obligation must be a backed
``pass`` or carry an explicit operator exclusion, or it exits non-zero. Unit tests proving the
readiness helper detects missing rows are NOT this verdict — this reads the real ledger and
artifact identities and prints the actual result.

Usage:
    python3 ci/android_rn_readiness.py
Exit: 0 only when the candidate is evidenced as ready; 1 otherwise.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_android_rn_qualification import (  # noqa: E402
    EMPTY_EVIDENCE,
    EXPECTED_IDS,
    LEDGER,
    parse_rows,
    qualification_rows,
    read,
    readiness_violations,
)


def main() -> int:
    rows = qualification_rows(parse_rows(read(LEDGER)))
    violations = readiness_violations(rows)
    total = len(EXPECTED_IDS)
    if not violations:
        print(f"READINESS: PASS — {total}/{total} required obligations evidenced")
        return 0
    print(f"READINESS: FAIL — {len(violations)}/{total} required obligations not evidenced")
    for entry in violations[:50]:
        print(f"  - {entry}")
    if len(violations) > 50:
        print(f"  ... and {len(violations) - 50} more")
    print(
        "M3 is not established: this is the final gate, not preview CI. "
        "Evidence must be attached per row, or an operator-approved exclusion recorded."
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
