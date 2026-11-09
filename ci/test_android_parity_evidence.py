"""Slice-07 parity evidence contract: the capture harness must be able to
produce every non-deferred visual fixture the parity matrix declares, and
captures must never silently claim a commit pin that was not verified."""

import pathlib
import re
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent

MATRIX = REPO_ROOT / "docs" / "parity" / "parity-matrix.md"
HARNESS = REPO_ROOT / "scripts" / "capture-parity-evidence"


class AndroidParityEvidenceTest(unittest.TestCase):
    def matrix_fixtures(self) -> set[str]:
        text = MATRIX.read_text(encoding="utf-8")
        lines = text.splitlines()
        header = next(
            index for index, line in enumerate(lines)
            if line.startswith("|") and "Visual fixture" in line
        )
        fixtures: set[str] = set()
        for line in lines[header + 2 :]:
            if not line.startswith("|"):
                break
            cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
            fixture_cell = cells[-1]
            if "deferred" in fixture_cell:
                continue
            for fixture in fixture_cell.split(","):
                name = fixture.strip()
                # A fixture may carry an operator note in parentheses.
                name = re.sub(r"\s*\(.*\)$", "", name).strip().strip("`")
                if name:
                    fixtures.add(name)
        return fixtures

    def harness_states(self) -> set[str]:
        text = HARNESS.read_text(encoding="utf-8")
        match = re.search(r'ALL_STATES="([^"]+)"', text)
        if match is None:
            self.fail("ALL_STATES not found in the capture harness")
        return set(match.group(1).split())

    def test_every_matrix_fixture_is_capturable(self) -> None:
        missing = self.matrix_fixtures() - self.harness_states()
        self.assertEqual(set(), missing)

    def test_the_harness_has_an_instruction_for_every_state(self) -> None:
        text = HARNESS.read_text(encoding="utf-8")
        state_block = text[text.index("state_instructions()") : text.index("capture_state()")]
        for state in self.harness_states():
            self.assertIn(f"{state})", state_block)

    def test_no_install_captures_carry_no_commit_pin(self) -> None:
        text = HARNESS.read_text(encoding="utf-8")
        self.assertIn('SHA="no-commit-pin"', text)
        self.assertIn('PROVENANCE="unverified-installed"', text)
        self.assertIn("appProvenance", text)

    def test_the_default_path_installs_before_capturing(self) -> None:
        text = HARNESS.read_text(encoding="utf-8")
        self.assertIn(":app:assemblePlayDebug", text)
        self.assertIn("android/app/build/outputs/apk/play/debug/app-play-debug.apk", text)
        self.assertIn('adb_run install -r', text)


if __name__ == "__main__":
    unittest.main()
