import json
import os
from pathlib import Path
import re
import stat
import struct
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch
import zipfile

from ci import android_release_artifacts as release
from ci.test_android_bundle import ABIS, LIBRARIES, SHIPPING_REGISTRATIONS, dex, elf, resource_table

ROOT = Path(__file__).resolve().parents[1]


def xml(edition="foss", **attrs):
    values = {"package": "com.lavasec.app" + (".foss" if edition == "foss" else ""),
              "android:versionName": "2.2.3", "android:versionCode": "202039999"}
    values.update(attrs)
    encoded = " ".join(f'{k}="{v}"' for k, v in values.items())
    return (f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" {encoded}>'
            '<uses-sdk android:minSdkVersion="28" android:targetSdkVersion="36" />'
            '<application /></manifest>').encode()


class AndroidReleaseArtifactsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        assets = ROOT / "android/app/src/main/assets/ExploreNarration"
        cls.audio = {"base/assets/ExploreNarration/" + p.name: p.read_bytes() for p in assets.iterdir() if p.is_file()}

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        self.root = self.work / "source"
        self.root.mkdir()
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.name", "Release fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        for p in release.LOCKS:
            self.write(p, b"fixture source pin\n")
        self.write("release/android-version.properties", b"versionName=2.2.3\nversionCode=202039999\n")
        self.write(".gitignore", b"android/app/build/\nandroid/app/src/main/assets/index.android.bundle\n")
        self.git("add", ".")
        self.git("commit", "-qm", "source")
        self.sha = self.git("rev-parse", "HEAD")
        self.git("update-ref", "refs/remotes/origin/main", self.sha)
        self.env = {"GITHUB_REPOSITORY": release.REPOSITORY, "GITHUB_SHA": self.sha,
                    "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/main",
                    "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1"}
        self.ctx = release.context(self.root, self.env)

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, stderr=subprocess.DEVNULL, text=True).strip()

    def write(self, relative, data):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def build_fixture(self):
        entries = {"base/assets/index.android.bundle": b"shipping JS", "base/dex/classes.dex": dex(SHIPPING_REGISTRATIONS)}
        entries.update(self.audio)
        entries.update({f"base/lib/{abi}/{lib}": elf(abi) for abi in ABIS for lib in LIBRARIES})
        self.write("android/app/src/main/assets/index.android.bundle", b"shipping JS")
        for edition in ("play", "foss"):
            bundle = self.root / f"android/app/build/outputs/bundle/{edition}Release/app-{edition}-release.aab"
            bundle.parent.mkdir(parents=True)
            with zipfile.ZipFile(bundle, "w") as z:
                for name, value in entries.items():
                    z.writestr(name, value)
                z.writestr("base/manifest/AndroidManifest.xml", b"protobuf decoded at SDK boundary")
                z.writestr("base/resources.pb", resource_table(package=("com.lavasec.app" + (".foss" if edition == "foss" else "")).encode()))
            for abi in ABIS:
                for lib in LIBRARIES:
                    self.write(f"android/app/build/intermediates/stripped_native_libs/{edition}Release/strip{edition.title()}ReleaseDebugSymbols/out/lib/{abi}/{lib}", elf(abi))
        apk = self.root / "android/app/build/outputs/apk/foss/release/app-foss-release-unsigned.apk"
        apk.parent.mkdir(parents=True)
        with zipfile.ZipFile(apk, "w") as z:
            for name, data in entries.items():
                z.writestr(name.removeprefix("base/dex/") if name.startswith("base/dex/") else name.removeprefix("base/"), data)
            z.writestr("AndroidManifest.xml", b"binary Android XML decoded at SDK boundary")
        # Exercise AGP's actual differing AAB/APK locations for baseline profiles.
        foss_bundle = self.root / "android/app/build/outputs/bundle/fossRelease/app-foss-release.aab"
        with zipfile.ZipFile(apk, "a") as a, zipfile.ZipFile(foss_bundle, "a") as b:
            for name in ("baseline.prof", "baseline.profm"):
                data = ("profile bytes " + name).encode()
                a.writestr("assets/dexopt/" + name, data)
                b.writestr("BUNDLE-METADATA/com.android.tools.build.profiles/" + name, data)
            metadata = b"appMetadataVersion=1.1\n"
            a.writestr("META-INF/com/android/build/gradle/app-metadata.properties", metadata)
            b.writestr("base/root/META-INF/com/android/build/gradle/app-metadata.properties", metadata)
            b.writestr("BUNDLE-METADATA/com.android.tools.build.gradle/app-metadata.properties", metadata)
        self.graph = self.work / "dependencies.txt"
        self.graph.write_bytes(b"fossReleaseRuntimeClasspath - Runtime classpath\n+--- org.example:floss:1.0\n")
        return apk

    def collect(self):
        out = self.work / "distribution"
        # The existing real runtime/ELF/narration/DEX verifiers still run. Only the
        # SDK binary-XML decoders and zipalign subprocess are stubbed in unit tests;
        # the public workflow exercises those with real Android outputs.
        with patch("ci.android_release_artifacts.dump_packaged_manifest", side_effect=lambda b, t: xml("foss" if "fossRelease" in str(b) else "play")), \
             patch("ci.verify_foss_boundary.dump_packaged_manifest", return_value=xml()), \
             patch("ci.android_release_artifacts.decode_apk_manifest", return_value=xml()), \
             patch("ci.android_release_artifacts.verify_zip_alignment"):
            release.package(self.root, self.ctx, out, self.work / "bundletool.jar", self.work / "apkanalyzer", self.work / "zipalign", self.graph)
        return out

    def test_manual_and_pr_coordinates_do_not_authorize_drafts(self):
        self.assertEqual("manual", self.ctx["mode"])
        self.assertEqual("", self.ctx["tag"])
        env = dict(self.env, GITHUB_EVENT_NAME="pull_request", GITHUB_REF="refs/pull/7/merge")
        self.assertEqual("validation", release.context(self.root, env)["mode"])
        for env in (self.env, env):
            with self.assertRaises(ValueError):
                release.context(self.root, env, publishing=True)

    def test_only_matching_stable_tags_can_prepare_a_draft(self):
        self.git("tag", "-a", "v2.2.3", "-m", "release")
        env = dict(self.env, GITHUB_EVENT_NAME="push", GITHUB_REF="refs/tags/v2.2.3")
        self.assertEqual("tag", release.context(self.root, env)["mode"])
        self.assertEqual(self.sha, release.context(self.root, env, publishing=True)["sourceSha"])
        self.assertEqual("2", release.context(self.root, dict(env, GITHUB_RUN_ATTEMPT="2"))["runAttempt"])
        for change in ({"GITHUB_REF": "refs/tags/v2.2.3-rc1"}, {"GITHUB_REF": "refs/tags/v2.2.4"},
                       {"GITHUB_REPOSITORY": "someone/fork"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                release.context(self.root, dict(env, **change))

    def test_main_can_advance_during_build_but_tag_cannot_move(self):
        self.git("tag", "v2.2.3")
        env = dict(self.env, GITHUB_EVENT_NAME="push", GITHUB_REF="refs/tags/v2.2.3")
        initial = release.context(self.root, env)
        self.git("commit", "--allow-empty", "-qm", "later main")
        later = self.git("rev-parse", "HEAD")
        self.git("update-ref", "refs/remotes/origin/main", later)
        self.git("checkout", "-q", "--detach", self.sha)
        self.assertEqual(initial, release.context(self.root, env))
        self.assertEqual(initial, release.context(self.root, env, publishing=True))
        self.git("tag", "-f", "v2.2.3", later)
        with self.assertRaises(ValueError):
            release.context(self.root, env, publishing=True)

    def test_wrong_checkout_dirty_source_bad_version_and_private_repo_fail_closed(self):
        for change in ({"GITHUB_SHA": "0" * 40}, {"GITHUB_REPOSITORY": "lavasecurity/lavasec-android-internal"},
                       {"GITHUB_EVENT_NAME": "pull_request_target"}, {"GITHUB_REF": "refs/heads/feature"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                release.context(self.root, dict(self.env, **change))
        self.write("untracked", b"dirty")
        with self.assertRaisesRegex(ValueError, "clean"):
            release.context(self.root, self.env)
        (self.root / "untracked").unlink()
        self.write("release/android-version.properties", b"versionName=2.2.3\nversionCode=1\n")
        with self.assertRaises(ValueError):
            release.release_defaults(self.root)

    def test_unmerged_tag_cannot_prepare_or_publish_release_artifacts(self):
        self.git("checkout", "-qb", "unreviewed")
        self.git("commit", "--allow-empty", "-qm", "unreviewed change")
        self.git("tag", "v2.2.3")
        env = dict(self.env, GITHUB_SHA=self.git("rev-parse", "HEAD"),
                   GITHUB_EVENT_NAME="push", GITHUB_REF="refs/tags/v2.2.3")
        for publishing in (False, True):
            with self.subTest(publishing=publishing), self.assertRaisesRegex(ValueError, "main history"):
                release.context(self.root, env, publishing=publishing)

    def test_actual_archive_payloads_and_source_are_bound_to_checksums(self):
        self.build_fixture()
        out = self.collect()
        manifest = release.verify(out, self.ctx, self.root)
        self.assertEqual({"com.lavasec.app", "com.lavasec.app.foss"}, {a["packageName"] for a in manifest["artifacts"]})
        self.assertTrue(all(a["signing"] == "unsigned" for a in manifest["artifacts"]))
        archive = self.work / "artifact.zip"
        with zipfile.ZipFile(archive, "w") as z:
            for path in out.iterdir():
                z.write(path, path.name)
        destination = self.work / "downloaded"
        release.unpack(archive, release.digest(archive), destination, self.ctx, self.root)
        self.assertEqual((out / release.SUMS).read_bytes(), (destination / release.SUMS).read_bytes())

    def test_wrong_identity_debug_sdk_and_edition_are_rejected(self):
        for payload in (xml("play"), xml(**{"android:versionCode": "1"}), xml(**{"android:versionName": "2.2.4"}),
                        xml().replace(b"<application />", b'<application android:debuggable="true" />'),
                        xml().replace(b'android:minSdkVersion="28"', b'android:minSdkVersion="27"')):
            with self.subTest(xml=payload), self.assertRaises(ValueError):
                release.identity(payload, "com.lavasec.app.foss", self.ctx)

    def test_foss_apk_cannot_add_code_or_differ_from_audited_bundle(self):
        apk = self.build_fixture()
        with zipfile.ZipFile(apk, "a") as z:
            z.writestr("classes2.dex", dex(["Lcom/google/android/gms/Hidden;"]))
        with self.assertRaisesRegex(ValueError, "payload"):
            self.collect()
        self.assertFalse((self.work / "distribution").exists())

    def test_signed_inputs_are_rejected_for_v1_and_v2(self):
        path = self.work / "signed.apk"
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("classes.dex", b"code")
            z.writestr("META-INF/CERT.RSA", b"signature")
        with self.assertRaisesRegex(ValueError, "unsigned"):
            release.unsigned(path)
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("classes.dex", b"code")
        data = bytearray(path.read_bytes())
        eocd = data.rfind(b"PK\x05\x06")
        central = struct.unpack_from("<I", data, eocd + 16)[0]
        block = b"padding!" + b"APK Sig Block 42"
        data[central:central] = block
        struct.pack_into("<I", data, eocd + len(block) + 16, central + len(block))
        path.write_bytes(data)
        with self.assertRaisesRegex(ValueError, "signing block"):
            release.unsigned(path)

    def test_profile_bytes_and_payload_aliases_cannot_be_hidden_by_layout_mapping(self):
        apk = self.build_fixture()
        bundle = self.root / "android/app/build/outputs/bundle/fossRelease/app-foss-release.aab"
        with zipfile.ZipFile(apk) as a:
            entries = {p: a.read(p) for p in a.namelist()}
        entries["assets/dexopt/baseline.prof"] = b"changed profile"
        with zipfile.ZipFile(apk, "w") as a:
            for p, value in entries.items():
                a.writestr(p, value)
        with self.assertRaisesRegex(ValueError, "differs"):
            release.apk_payload(apk, bundle)
        with zipfile.ZipFile(bundle, "a") as b:
            b.writestr("base/root/assets/index.android.bundle", b"shadowed JS")
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            release.apk_payload(apk, bundle)

    def test_tampered_bytes_source_context_extra_files_and_links_are_rejected(self):
        self.build_fixture()
        out = self.collect()
        apk = out / release.names(self.ctx)["foss"]
        original = apk.read_bytes()
        apk.write_bytes(original + b"tamper")
        with self.assertRaises(ValueError):
            release.verify(out, self.ctx, self.root)
        apk.write_bytes(original)
        changed = dict(self.ctx, sourceSha="f" * 40)
        with self.assertRaises(ValueError):
            release.verify(out, changed, self.root)
        (out / "unexpected").write_text("extra")
        with self.assertRaises(ValueError):
            release.verify(out, self.ctx, self.root)
        (out / "unexpected").unlink()
        saved = self.work / "binary.apk"
        apk.rename(saved)
        apk.symlink_to(saved)
        with self.assertRaises(ValueError):
            release.verify(out, self.ctx, self.root)

    def test_archive_digest_traversal_and_symlink_fail_before_extracting(self):
        self.build_fixture()
        out = self.collect()
        for kind in ("traversal", "symlink", "digest"):
            archive = self.work / (kind + ".zip")
            with zipfile.ZipFile(archive, "w") as z:
                for p in out.iterdir():
                    z.write(p, p.name)
                if kind == "traversal":
                    z.writestr("../outside", b"bad")
                if kind == "symlink":
                    p = zipfile.ZipInfo("link")
                    p.external_attr = (stat.S_IFLNK | 0o777) << 16
                    z.writestr(p, "outside")
            destination = self.work / kind
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                release.unpack(archive, "0" * 64 if kind == "digest" else release.digest(archive), destination, self.ctx, self.root)
            self.assertFalse(destination.exists())


class AndroidReleaseWorkflowTest(unittest.TestCase):
    def test_all_release_jobs_use_standard_github_runners_and_build_is_unprivileged(self):
        source = (ROOT / ".github/workflows/android-release.yml").read_text()
        self.assertEqual(["ubuntu-24.04", "ubuntu-24.04"], re.findall(r"runs-on: (.+)", source))
        build, draft = source.split("\n  draft:", 1)
        for forbidden in ("secrets.", "environment:", "id-token:", "contents: write", "gh release create"):
            self.assertNotIn(forbidden, build)
        self.assertIn("github.repository == 'lavasecurity/lavasec-android'", build)
        self.assertIn("github.event_name == 'push'", draft)
        self.assertIn("needs.build.outputs.mode == 'tag'", draft)
        self.assertIn("contents: write", draft)
        self.assertIn("--draft --latest=false", draft)
        self.assertIn("--verify-tag", draft)
        self.assertIn("SOURCE_SHA: ${{ github.sha }}", draft)
        self.assertIn("RELEASE_TAG: ${{ github.ref_name }}", draft)
        self.assertNotIn("needs.build.outputs.source_sha", draft)
        self.assertNotIn("needs.build.outputs.tag", draft)
        self.assertLess(draft.index("android_release_artifacts unpack"), draft.index("gh release create"))
        for forbidden in ("--clobber", "google-github-actions/auth", "upload_to_play_store", "./gradlew", "npm ci"):
            self.assertNotIn(forbidden, draft)
        self.assertIn("retention-days: 1", build)
        self.assertIn("--no-build-cache", build)

    def test_actual_draft_script_never_publishes_or_replaces_a_release(self):
        source = (ROOT / ".github/workflows/android-release.yml").read_text()
        script = textwrap.dedent(source.split("- name: Create a new draft without replacing existing assets", 1)[1].split("run: |", 1)[1])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "verified-release-assets").mkdir()
            (root / "verified-release-assets/SHA256SUMS").write_text("fixture")
            command = root / "gh"
            command.write_text("#!/usr/bin/env python3\n" + textwrap.dedent('''\
                import json, os, sys
                args = sys.argv[1:]
                if args[0] == 'api':
                    print(os.environ['DRAFT_REMOTE_SOURCE'])
                elif args[:2] == ['release', 'view']:
                    sys.exit(0 if os.environ['DRAFT_ALREADY_EXISTS'] == 'true' else 1)
                elif args[:2] == ['release', 'create']:
                    with open(os.environ['DRAFT_CAPTURE'], 'w') as f:
                        json.dump(args, f)
                else:
                    sys.exit(2)
                '''))
            command.chmod(0o755)
            for kind, remote, existing, success in (("fresh", "a" * 40, "false", True),
                                                    ("exists", "a" * 40, "true", False),
                                                    ("moved", "b" * 40, "false", False)):
                capture = root / (kind + ".json")
                env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                           RUNNER_TEMP=str(root), GITHUB_REPOSITORY=release.REPOSITORY,
                           SOURCE_SHA="a" * 40, RELEASE_TAG="v2.2.3", VERSION_NAME="2.2.3",
                           DRAFT_REMOTE_SOURCE=remote, DRAFT_ALREADY_EXISTS=existing, DRAFT_CAPTURE=str(capture))
                result = subprocess.run(["bash", "-c", script], env=env, text=True, capture_output=True, timeout=10)
                with self.subTest(case=kind):
                    self.assertEqual(success, result.returncode == 0, result.stderr)
                    self.assertEqual(success, capture.exists())
                    if success:
                        args = json.loads(capture.read_text())
                        self.assertIn("--draft", args)
                        self.assertIn("--verify-tag", args)
                        self.assertIn("--latest=false", args)
                        self.assertNotIn("--clobber", args)


if __name__ == "__main__":
    unittest.main()
