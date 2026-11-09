"""Canonical React Native source provenance contract (plan 309 F02, convergence C2).

The provenance manifest pins every committed canonical file and classifies Android overlays and
reviewed divergences. These checks prove the verifier is fail-closed: an edited, added, removed or
unclassified canonical file must fail, not pass vacuously. Linux/Android CI has Node available
because the RN package is built there; the checks skip if Node is absent.
"""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RN = ROOT / "android" / "react-native"
CHECKER = RN / "scripts" / "check-canonical-source.mjs"
MANIFEST = RN / "scripts" / "canonical-source.manifest.json"
CANONICAL = RN / "canonical"
CANONICAL_REVISION = "4e552f33ab973c32539071f327803198f40279f5"
KNOWN_CLASSES = {"upstream", "ios-platform", "overlay", "android-only", "divergence"}


def have_node() -> bool:
    return shutil.which("node") is not None


@unittest.skipUnless(have_node(), "Node.js is required for the provenance contract")
class CanonicalProvenanceTest(unittest.TestCase):
    def run_checker(self, canonical_root: Path, manifest: Path) -> subprocess.CompletedProcess:
        env = dict(os.environ, CANONICAL_ROOT=str(canonical_root), CANONICAL_MANIFEST=str(manifest))
        return subprocess.run(
            ["node", str(CHECKER)], cwd=str(RN), env=env, capture_output=True, text=True
        )

    def test_manifest_pins_the_canonical_revision_and_is_fully_classified(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.assertEqual(1, manifest["schema"])
        self.assertEqual(CANONICAL_REVISION, manifest["canonical"]["revision"])
        self.assertTrue(manifest["files"], "manifest must list files")
        for entry in manifest["files"]:
            with self.subTest(path=entry.get("path")):
                self.assertIn(entry["class"], KNOWN_CLASSES)
                if entry["class"] in {"overlay", "android-only", "divergence"}:
                    self.assertTrue(entry.get("rationale", "").strip(), "reviewed class needs a rationale")

    def test_committed_tree_passes(self) -> None:
        result = self.run_checker(CANONICAL, MANIFEST)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_edited_canonical_file_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "canonical"
            shutil.copytree(CANONICAL, root)
            (root / "app" / "contract.ts").write_text("// tampered\n", encoding="utf-8")
            result = self.run_checker(root, MANIFEST)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("contract.ts", result.stderr)

    def test_added_canonical_file_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "canonical"
            shutil.copytree(CANONICAL, root)
            (root / "app" / "unreviewed.ts").write_text("export {}\n", encoding="utf-8")
            result = self.run_checker(root, MANIFEST)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("unreviewed.ts", result.stderr)

    def test_removed_canonical_file_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "canonical"
            shutil.copytree(CANONICAL, root)
            (root / "app" / "contract.ts").unlink()
            result = self.run_checker(root, MANIFEST)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("contract.ts", result.stderr)


if __name__ == "__main__":
    unittest.main()
