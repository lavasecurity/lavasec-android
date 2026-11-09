import importlib.util
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def load_policy_module():
    policy_path = ROOT / "ci" / "release_policy.py"
    spec = importlib.util.spec_from_file_location("android_release_policy", policy_path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class AndroidReleasePolicyTest(unittest.TestCase):
    def setUp(self) -> None:
        policy_path = ROOT / "ci" / "release_policy.py"
        self.assertTrue(policy_path.is_file(), f"missing {policy_path}")
        self.policy = load_policy_module()

    def test_gradle_export_and_preflight_share_one_aligned_version(self) -> None:
        self.assertEqual(("2.2.3", 202_039_999), self.policy.release_defaults())
        self.assertEqual(202_030_003, self.policy.version_code("internal", "v2.2.3-rc3"))
        self.assertEqual(202_039_999, self.policy.version_code("external", "v2.2.3"))

    def test_internal_accepts_strict_rc_tags(self) -> None:
        for tag in ("v0.1.0-rc1", "v12.34.56-rc789"):
            with self.subTest(tag=tag):
                self.assertTrue(self.policy.validate_tag("internal", tag))

    def test_internal_rejects_every_non_strict_rc_shape(self) -> None:
        rejected = (
            "v0.1.0-rc0",
            "v0.1.0",
            "release-v0.1.0-rc1",
            "v0.1.0-rc1-extra",
            " v0.1.0-rc1",
            "v0.1.0-rc1 ",
            "v00.1.0-rc1",
            "v0.01.0-rc1",
            "v0.1.00-rc1",
            "v0.1.0-rc01",
        )
        for tag in rejected:
            with self.subTest(tag=tag):
                self.assertFalse(self.policy.validate_tag("internal", tag))

    def test_external_accepts_only_clean_semver_tags(self) -> None:
        for tag in ("v0.1.0", "v12.34.56"):
            with self.subTest(tag=tag):
                self.assertTrue(self.policy.validate_tag("external", tag))

        rejected = (
            "v0.1.0-rc1",
            "v0.1.0-beta1",
            "v0.1.0+1",
            "v00.1.0",
            "v0.01.0",
            "v0.1.00",
            " v0.1.0",
            "v0.1.0 ",
        )
        for tag in rejected:
            with self.subTest(tag=tag):
                self.assertFalse(self.policy.validate_tag("external", tag))

    def test_channels_derive_fixed_repositories_and_nonproduction_tracks(self) -> None:
        self.assertEqual(
            {
                "repository": "lavasecurity/lavasec-android-internal",
                "track": "internal",
                "packageName": "com.lavasec.app",
            },
            self.policy.derive_release("internal"),
        )
        self.assertEqual(
            {
                "repository": "lavasecurity/lavasec-android",
                "track": "alpha",
                "packageName": "com.lavasec.app",
            },
            self.policy.derive_release("external"),
        )
        for channel in ("internal", "external"):
            self.assertNotIn("production", self.policy.derive_release(channel).values())

    def test_unknown_channels_are_rejected(self) -> None:
        with self.assertRaises(ValueError):
            self.policy.validate_tag("production", "v0.1.0")
        with self.assertRaises(ValueError):
            self.policy.derive_release("production")

    def test_version_code_is_monotonic_and_bounded(self) -> None:
        self.assertEqual(1_000_003, self.policy.version_code("internal", "v0.1.0-rc3"))
        tags = [("internal", "v0.1.0-rc1"), ("internal", "v0.1.0-rc9998"),
                ("external", "v0.1.0"), ("internal", "v0.1.1-rc1"),
                ("external", "v0.1.1"), ("internal", "v0.2.0-rc1")]
        codes = [self.policy.version_code(*pair) for pair in tags]
        self.assertEqual(codes, sorted(set(codes)))
        for channel, tag in [("internal", "v0.1.0-rc9999"), ("external", "v21.0.0"),
                             ("external", "v0.100.0"), ("internal", "v0.0.100-rc1")]:
            with self.assertRaises(ValueError):
                self.policy.version_code(channel, tag)

    def test_release_tag_cli_uses_the_same_strict_policy(self) -> None:
        script = ROOT / "scripts" / "verify-android-release-tag"
        self.assertTrue(script.is_file(), f"missing {script}")
        accepted = subprocess.run(
            [str(script), "internal", "v0.1.0-rc1"],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        rejected = subprocess.run(
            [str(script), "internal", "v0.1.0"],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, accepted.returncode, accepted.stderr)
        self.assertNotEqual(0, rejected.returncode)


if __name__ == "__main__":
    unittest.main()
