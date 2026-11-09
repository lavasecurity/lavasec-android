"""F25/F26 qualification contract (plan 309).

The F25 evidence ledger and F26 release handoff are scaffolds: they enumerate every
required journey, native flow and shared atomic family, but they carry no device,
signing or live-service evidence. This check is deliberately fail-closed — it names
every row the ledger must contain, and it refuses a row that claims ``pass`` without a
non-empty, redacted evidence link. A scaffold that claims a pass it cannot back is worse
than no ledger at all.

Row groups: plan 309 U01-U18 journeys, the plan 309 native-flow/atomic/AX matrices, the
plan 308 A01-A15 integrated backend acceptance scenarios, and the Q1-Q4 device-verification
boundary checklist. The A/DV rows enumerate the stop boundary: work that cannot be
completed from this workspace and must be handed to an operator.
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LEDGER = ROOT / "docs" / "android-rn-qualification-ledger.md"
HANDOFF = ROOT / "docs" / "android-rn-release-handoff.md"
DEVICE_HANDOFF = ROOT / "docs" / "android-device-verification-handoff.md"

# --- Canonical row keys the ledger must enumerate ---------------------------------
# Backend acceptance scenarios: plan 308 "Acceptance scripts for the integrated candidate" A01-A15.
BACKEND_SCENARIO_IDS = tuple(f"A{index:02d}" for index in range(1, 16))

# Device-verification boundary (Q1-Q4 + capability lanes) that cannot run from this workspace.
DEVICE_CHECKLIST_IDS = (
    "DV-Q1-ENV",
    "DV-Q2-COMBINED",
    "DV-Q3-SIGNED",
    "DV-Q4-HANDOFF",
    "DV-CAP-SIGNING",
    "DV-CAP-OAUTH-GOOGLE",
    "DV-CAP-OAUTH-APPLE",
    "DV-CAP-PLAY",
    "DV-CAP-INTEGRITY",
    "DV-CAP-BACKUP",
    "DV-CAP-REPORT",
    "DV-CAP-DEVICE",
    "DV-CAP-EMULATOR",
)

# Journey scenarios: plan 309 "Qualification matrix and evidence rules" U01-U18.
JOURNEY_IDS = tuple(f"U{index:02d}" for index in range(1, 19))

# Native boundaries: three module contracts, seven generated components, and the
# native page/modal/system placements from the plan's native implementation inventory.
NATIVE_FLOW_IDS = (
    "NF-MOD-APP",
    "NF-MOD-APPEARANCE",
    "NF-MOD-REVIEW",
    "NF-CMP-TEXTFIELD",
    "NF-CMP-CHOICE",
    "NF-CMP-SLIDER",
    "NF-CMP-SWITCH",
    "NF-CMP-CONTEXTMENU",
    "NF-CMP-DECORATION",
    "NF-CMP-NATIVEPAGE",
    "NF-PAGE-ACCOUNT",
    "NF-PAGE-PASSCODE",
    "NF-PAGE-PURCHASE",
    "NF-PAGE-BACKUP",
    "NF-PAGE-CUSTOMENTRY",
    "NF-PAGE-FEEDBACK",
    "NF-PAGE-VPNCHAINING",
    "NF-PAGE-AUTOMATION",
    "NF-PAGE-QA",
    "NF-PAGE-FILTERFORMS",
    "NF-PAGE-SHARE",
    "NF-SYS-VPNPERMISSION",
    "NF-SYS-NOTIFICATIONS",
    "NF-SYS-OSSETTINGS",
)

# Shared F04/F22 atomic families from the fixture-grid and atom audit.
ATOMIC_FAMILY_IDS = (
    "AF-TYPE",
    "AF-CARD",
    "AF-PANEL",
    "AF-ACTION",
    "AF-TOGGLE",
    "AF-CHOICE",
    "AF-STEPPER",
    "AF-PAGES",
    "AF-DECORATION",
    "AF-IDENTITY",
    "AF-LEGEND",
    "AF-ROW",
    "AF-ICON",
    "AF-PROPORTION",
)

# F23 interruption kinds (android/react-native/tests/interruptionHarness.tsx).
INTERRUPTION_KINDS = (
    "blur-back",
    "tab-change",
    "background-foreground",
    "rotation",
    "process-death",
    "native-module-teardown",
    "graph-retirement",
    "account-switch",
    "tier-change",
    "privacy-revocation",
)
INTERRUPTION_IDS = tuple(f"INT-{kind}" for kind in INTERRUPTION_KINDS)

# F22 locale inventory (the ten shipped Android locales).
LOCALE_IDS = tuple(
    f"A11Y-LOCALE-{locale}"
    for locale in ("en", "de", "es", "fr", "it", "ja", "ko", "pt-BR", "zh-CN", "zh-TW")
)

# F22 structural/interaction assertions the device lane must evidence.
A11Y_STRUCTURAL_IDS = (
    "A11Y-CONTROL-LABELS",
    "A11Y-HEADINGS",
    "A11Y-DECORATION-HIDDEN",
    "A11Y-CHART-ALTERNATIVE",
    "A11Y-PAGE-POSITION",
    "A11Y-RESPONSIVE",
    "A11Y-TEXT-SIZE",
    "A11Y-REDUCED-MOTION",
    "A11Y-CONTRAST",
)

EXPECTED_IDS = (
    BACKEND_SCENARIO_IDS
    + JOURNEY_IDS
    + NATIVE_FLOW_IDS
    + ATOMIC_FAMILY_IDS
    + INTERRUPTION_IDS
    + LOCALE_IDS
    + A11Y_STRUCTURAL_IDS
    + DEVICE_CHECKLIST_IDS
)

VALID_STATUSES = {"not-run", "pass", "fail", "blocked"}
# Cells that mean "nothing recorded yet"; a `pass` may not carry one.
EMPTY_EVIDENCE = {"", "—", "–", "-", "n/a", "none", "tbd", "pending", "[]"}
# The release runbook sections an operator handoff must contain.
HANDOFF_SECTIONS = (
    "Revisions and environment",
    "Build and sign through the protected RC lane",
    "Immutable digest and installed-identity verification",
    "Internal-track upload",
    "Install and update preservation",
    "Cold-start and critical smoke",
    "Rollback",
    "credential retrieval",
    "not authorized",
    "not performed",
    "tag-release.yml",
    "release/android/",
    "ubicloud-standard-4",
    "play-internal",
    "release-controller-tests.yml",
    "verify_android_bundle.py",
)

# A qualification row: first cell is the backticked Test ID and it has the full 12 columns.
_ROW = re.compile(r"^\|\s*`(?P<id>[^`]+)`\s*\|(?P<body>.*)\|\s*$")


def read(path: Path) -> str:
    assert path.is_file(), f"missing {path}"
    return path.read_text(encoding="utf-8")


def parse_rows(text: str) -> dict[str, list[str]]:
    """Map every backticked-first-cell table row to its stripped cells."""
    rows: dict[str, list[str]] = {}
    for line in text.splitlines():
        match = _ROW.match(line)
        if match is None:
            continue
        cells = [cell.strip() for cell in match.group("body").split("|")]
        # body excludes the leading empty cell before the ID; cells[0] is the ID.
        row_id = match.group("id").strip()
        rows[row_id] = cells
    return rows


def qualification_rows(rows: dict[str, list[str]]) -> dict[str, tuple[str, str]]:
    """Return {id: (status, evidence)} for rows with the full 12-column shape."""
    result: dict[str, tuple[str, str]] = {}
    for row_id, cells in rows.items():
        # `body` is every cell after the ID, so a 12-column row has 11 cells; status is
        # second-to-last and evidence last. Cells may be wrapped in backticks.
        if len(cells) < 11:
            continue
        status = cells[-2].strip().strip("`").lower()
        evidence = cells[-1].strip().strip("`")
        result[row_id] = (status, evidence)
    return result


def is_consistent(status: str, evidence: str) -> bool:
    """True when a row does not claim a pass without evidence."""
    if status != "pass":
        return True
    return evidence.strip().lower() not in EMPTY_EVIDENCE


def readiness_violations(
    rows: dict[str, tuple[str, str]],
    approved_exclusions: dict[str, str] | None = None,
) -> list[str]:
    """The FINAL readiness verdict, distinct from the preview shape check above.

    Returns one entry per required row that is not a backed ``pass`` and does not carry an explicit,
    rationale-bearing operator exclusion. A green ledger-schema test is never this verdict: a
    ``not-run``/``blocked``/``failed``/missing row fails, a ``pass`` with no evidence link fails,
    and an exclusion without a rationale fails. Ordinary preview CI still runs the shape checks and
    tolerates the all-``not-run`` scaffold.
    """
    exclusions = approved_exclusions or {}
    violations: list[str] = []
    for row_id in EXPECTED_IDS:
        if row_id in exclusions:
            if not exclusions[row_id].strip():
                violations.append(f"{row_id}: exclusion without a rationale")
            continue
        status, evidence = rows.get(row_id, ("missing", ""))
        if status != "pass":
            violations.append(f"{row_id}: {status}")
        elif evidence.strip().lower() in EMPTY_EVIDENCE:
            violations.append(f"{row_id}: pass without evidence")
    return violations


class AndroidRnQualificationTest(unittest.TestCase):
    def test_both_documents_exist(self) -> None:
        self.assertTrue(LEDGER.is_file(), f"missing {LEDGER}")
        self.assertTrue(HANDOFF.is_file(), f"missing {HANDOFF}")
        read(LEDGER)
        read(HANDOFF)

    def test_ledger_contains_every_required_row_key(self) -> None:
        rows = parse_rows(read(LEDGER))
        present = set(rows)
        missing = sorted(set(EXPECTED_IDS) - present)
        self.assertEqual([], missing, f"ledger is missing rows: {missing}")
        # The row keys must be unique; a duplicate ID would hide a missing one.
        self.assertEqual(
            len(EXPECTED_IDS),
            sum(1 for key in rows if key in set(EXPECTED_IDS)),
        )

    def test_every_row_declares_a_known_status(self) -> None:
        parsed = qualification_rows(parse_rows(read(LEDGER)))
        self.assertGreaterEqual(len(parsed), len(EXPECTED_IDS))
        for row_id, (status, _evidence) in parsed.items():
            with self.subTest(row=row_id):
                self.assertIn(status, VALID_STATUSES, f"{row_id} has status {status!r}")

    def test_no_pass_without_an_evidence_link(self) -> None:
        parsed = qualification_rows(parse_rows(read(LEDGER)))
        for row_id, (status, evidence) in parsed.items():
            with self.subTest(row=row_id):
                self.assertTrue(
                    is_consistent(status, evidence),
                    f"{row_id} claims pass with no evidence link",
                )

    def test_the_consistency_check_is_fail_closed(self) -> None:
        # Prove the predicate itself rejects a bare pass rather than passing vacuously.
        self.assertFalse(is_consistent("pass", ""))
        self.assertFalse(is_consistent("pass", "—"))
        self.assertTrue(is_consistent("pass", "https://example.invalid/run/1"))
        self.assertTrue(is_consistent("not-run", ""))
        self.assertTrue(is_consistent("blocked", ""))

    def test_authored_scaffold_claims_no_pass(self) -> None:
        # This scaffold is authored without device/signing/live-service access. Relax
        # this only by attaching real evidence to the rows being marked pass.
        parsed = qualification_rows(parse_rows(read(LEDGER)))
        passes = sorted(row_id for row_id, (status, _e) in parsed.items() if status == "pass")
        self.assertEqual([], passes, f"unbacked pass rows: {passes}")

    def test_ledger_names_the_not_evidenced_gaps(self) -> None:
        text = read(LEDGER)
        self.assertIn("Not-evidenced / blocked", text)
        for token in ("device", "signing", "live-service", "not-run"):
            self.assertIn(token, text.lower())

    def test_handoff_document_contains_the_operator_sections(self) -> None:
        text = read(HANDOFF)
        for section in HANDOFF_SECTIONS:
            with self.subTest(section=section):
                self.assertIn(section, text)

    def test_handoff_authorizes_no_operation(self) -> None:
        text = read(HANDOFF)
        self.assertIn("Not authorized", text)
        self.assertIn("Not performed", text)

    def test_device_handoff_enumerates_the_stop_boundary(self) -> None:
        # The close-out handoff must exist and name every device-verification checklist row it
        # hands off, so a missing lane cannot be silently dropped.
        self.assertTrue(DEVICE_HANDOFF.is_file(), f"missing {DEVICE_HANDOFF}")
        text = read(DEVICE_HANDOFF)
        for row_id in DEVICE_CHECKLIST_IDS:
            with self.subTest(row=row_id):
                self.assertIn(row_id, text)
        for lane in ("Q1", "Q2", "Q3", "Q4"):
            with self.subTest(lane=lane):
                self.assertIn(lane, text)
        self.assertIn("not performed", text.lower())


class AndroidRnReadinessTest(unittest.TestCase):
    """Plan 309 exit criterion: preview CI can pass while final readiness fails."""

    def test_the_all_not_run_scaffold_fails_final_readiness(self) -> None:
        parsed = qualification_rows(parse_rows(read(LEDGER)))
        violations = readiness_violations(parsed)
        self.assertTrue(violations, "an all-not-run scaffold must not pass final readiness")

    def test_a_fully_evidenced_fixture_passes_readiness(self) -> None:
        complete = {row_id: ("pass", "https://example.invalid/run/1") for row_id in EXPECTED_IDS}
        self.assertEqual([], readiness_violations(complete))

    def test_readiness_rejects_missing_placeholder_failed_and_blocked_rows(self) -> None:
        complete = {row_id: ("pass", "https://example.invalid/run/1") for row_id in EXPECTED_IDS}
        complete["U01"] = ("pass", "—")
        complete["U02"] = ("not-run", "")
        complete["U03"] = ("fail", "https://example.invalid/run/3")
        complete["U04"] = ("blocked", "")
        violations = readiness_violations(complete)
        for row_id in ("U01", "U02", "U03", "U04"):
            with self.subTest(row=row_id):
                self.assertTrue(any(v.startswith(f"{row_id}:") for v in violations))
        # A missing row is a violation, not an omission.
        del complete["U05"]
        self.assertTrue(any(v.startswith("U05:") for v in readiness_violations(complete)))

    def test_readiness_honours_only_rationale_bearing_exclusions(self) -> None:
        complete = {row_id: ("pass", "https://example.invalid/run/1") for row_id in EXPECTED_IDS}
        complete["U04"] = ("blocked", "")
        complete["U05"] = ("not-run", "")
        self.assertTrue(readiness_violations(complete))
        self.assertEqual(
            [],
            readiness_violations(complete, {
                "U04": "operator-approved: platform-impossible on Android",
                "U05": "operator-approved: iOS-only surface",
            }),
        )
        # A blank rationale is not an approved exclusion.
        self.assertTrue(
            any("exclusion without a rationale" in v
                for v in readiness_violations(complete, {"U04": "  ", "U05": "operator-approved"})),
        )

    def test_preview_contract_passes_while_readiness_fails(self) -> None:
        # The separation itself is the exit criterion: the shape/evidence checks hold on the
        # scaffold (no unbacked pass), yet readiness fails because required rows are not-run.
        parsed = qualification_rows(parse_rows(read(LEDGER)))
        for row_id, (status, evidence) in parsed.items():
            self.assertTrue(is_consistent(status, evidence), f"{row_id} unbacked pass")
        self.assertTrue(readiness_violations(parsed))


if __name__ == "__main__":
    unittest.main()
