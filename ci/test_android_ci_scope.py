"""Exercise actual event diffs, renames and trust boundaries used to schedule CI."""
import subprocess
import tempfile
import unittest
from pathlib import Path
from ci.android_ci_scope import classify, changed_files

INTERNAL = "lavasecurity/lavasec-android-internal"
PUBLIC = "lavasecurity/lavasec-android"


class AndroidCiScopeTest(unittest.TestCase):
    def scope(self, files, event="pull_request", repository=INTERNAL, force=False):
        return classify(files, event=event, repository=repository, force_full=force)

    def test_docs_report_without_native_or_device_work(self):
        for repo in (INTERNAL, PUBLIC):
            for event in ("pull_request", "push"):
                result = self.scope(["README.md", "docs/building.md"], event, repo)
                self.assertTrue(result["docs_only"])
                self.assertFalse(any(result[k] for k in result if k != "docs_only"))

    def test_javascript_is_validated_without_rebuilding_native(self):
        for path in ("android/react-native/src/screens/Guard.tsx",
                     "android/react-native/src/NativeLavaApp.ts",
                     "android/react-native/canonical/src/ui/Guard.tsx",
                     "android/react-native/scripts/canonical-source.manifest.json",
                     "android/react-native/tests/shared-ui-reference.json",
                     "android/react-native/tests/guard.test.tsx"):
            with self.subTest(path=path):
                self.assertFalse(self.scope([path])["native_required"])
                self.assertFalse(self.scope([path])["docs_only"])
                self.assertTrue(self.scope([path], "push")["qualification_required"])

    def test_native_abi_dependencies_and_unknown_paths_rebuild(self):
        for path in ("android/app/src/main/kotlin/App.kt", "android/app/src/main/res/values/strings.xml",
                     "android/react-native/specs/NativeLavaApp.ts", "android/react-native/package-lock.json",
                     "android/react-native/canonical/specs/NativeLavaApp.android.ts",
                     "android/react-native/tests/unknown-helper.mjs", "android/react-native/metro.config.js",
                     "android/gradle/libs.versions.toml", "ci/android_ci_scope.py",
                     ".github/workflows/android.yml", "unclassified-input"):
            with self.subTest(path=path):
                self.assertTrue(self.scope([path])["native_required"])
                self.assertFalse(self.scope([path])["full_build"])
                self.assertTrue(self.scope([path], repository=PUBLIC)["full_build"])
                self.assertTrue(self.scope([path], "push")["full_build"])

    def test_engine_scope_is_independent_of_kotlin(self):
        self.assertFalse(self.scope(["android/vpn/Owner.kt"])["engine_required"])
        for path in ("ThirdParty/doq-core/src/lib.rs", "scripts/build-wireguard-core-android.sh"):
            self.assertTrue(self.scope([path])["engine_required"])

    def test_force_and_unknown_ranges_never_suppress_checks(self):
        for files in (None, [], ["README.md"]):
            for event, force in (("workflow_dispatch", False), ("pull_request", True)):
                result = self.scope(files, event, force=force)
                self.assertFalse(result["docs_only"])
                self.assertTrue(all(result[k] for k in result if k != "docs_only"))
        for files in (None, []):
            result = self.scope(files)
            self.assertFalse(result["docs_only"])
            self.assertTrue(result["native_required"])

    def test_complete_git_diff_includes_deletions_renames_and_more_than_300_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(["git", *args], cwd=root, text=True).strip()
            git("init", "-q")
            git("config", "user.name", "CI fixture")
            git("config", "user.email", "ci@example.invalid")
            git("config", "commit.gpgsign", "false")
            (root / "Owner.kt").write_text("class Owner\n")
            git("add", "."); git("commit", "-qm", "base")
            base = git("rev-parse", "HEAD")
            (root / "docs").mkdir()
            (root / "Owner.kt").rename(root / "docs/Owner.md")
            for index in range(350):
                (root / f"docs/{index}.md").write_text("docs\n")
            git("add", "-A"); git("commit", "-qm", "move and docs")
            head = git("rev-parse", "HEAD")
            files = changed_files("pull_request", base, head, cwd=root)
            self.assertEqual(352, len(files))
            self.assertIn("Owner.kt", files)
            self.assertTrue(self.scope(files)["native_required"])
            self.assertIsNone(changed_files("push", "0" * 40, head, cwd=root))
            self.assertIsNone(changed_files("push", "f" * 40, head, cwd=root))
            self.assertIsNone(changed_files("push", "--help", head, cwd=root))


if __name__ == "__main__":
    unittest.main()
