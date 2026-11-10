import base64
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from ci import android_play_signing as signing
from ci import android_release_artifacts as build
from ci import test_android_release_artifacts as fixtures

ROOT = Path(__file__).resolve().parents[1]


class ProtectedPlaySigningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixtures.AndroidReleaseArtifactsTest.setUpClass()
        cls.keys = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.keys.cleanup)
        cls.key = Path(cls.keys.name) / "fixture.jks"
        cls.password = "temporary-test-key-password"
        cls.tool_env = dict(os.environ, FIXTURE_PASSWORD=cls.password)
        subprocess.run(["keytool", "-genkeypair", "-keystore", str(cls.key), "-storetype", "JKS",
                        "-storepass:env", "FIXTURE_PASSWORD", "-keypass:env", "FIXTURE_PASSWORD",
                        "-alias", "fixture-upload", "-keyalg", "RSA", "-keysize", "2048",
                        "-sigalg", "SHA256withRSA", "-validity", "3650", "-dname", "CN=Disposable signing fixture"],
                       env=cls.tool_env, capture_output=True, check=True, timeout=60)
        certificate = subprocess.check_output(["keytool", "-exportcert", "-keystore", str(cls.key),
                                               "-storepass:env", "FIXTURE_PASSWORD", "-alias", "fixture-upload"], env=cls.tool_env, stderr=subprocess.PIPE)
        cls.expected = {"schemaVersion": 1, "keyAlias": "fixture-upload",
                        "storeSha256": build.digest(cls.key),
                        "certificateSha256": hashlib.sha256(certificate).hexdigest()}

    def setUp(self):
        self.fixture = fixtures.AndroidReleaseArtifactsTest(methodName="runTest")
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root
        self.fixture.write(signing.POLICY, build.canonical(self.expected))
        self.fixture.write("ci/VerifyPlaySignature.java", (ROOT / "ci/VerifyPlaySignature.java").read_bytes())
        self.fixture.git("add", ".")
        self.fixture.git("commit", "-qm", "reviewed fixture signing policy")
        sha = self.fixture.git("rev-parse", "HEAD")
        self.fixture.git("update-ref", "refs/remotes/origin/main", sha)
        self.fixture.env["GITHUB_SHA"] = sha
        self.env = dict(os.environ, **self.fixture.env,
                        ANDROID_UPLOAD_KEYSTORE_BASE64=base64.b64encode(self.key.read_bytes()).decode(),
                        ANDROID_UPLOAD_KEYSTORE_PASSWORD=self.password, ANDROID_UPLOAD_KEY_PASSWORD=self.password)
        self.ctx = signing.signing_context(self.root, self.env)
        self.fixture.ctx = self.ctx
        self.fixture.build_fixture()
        self.unsigned = self.fixture.collect()
        self.output = self.fixture.work / "play-signed"

    def signed(self):
        with contextlib.redirect_stdout(io.StringIO()):
            signing.sign(self.unsigned, self.output, self.ctx, self.root, self.env)
        return self.output

    def rewrite_zip(self, path, changes):
        with zipfile.ZipFile(path) as z:
            entries = {n: z.read(n) for n in z.namelist()}
        entries.update(changes)
        with zipfile.ZipFile(path, "w") as z:
            for name, value in entries.items():
                z.writestr(name, value)

    def refresh_base_hashes(self, directory):
        manifest = json.loads((directory / build.MANIFEST).read_bytes())
        for item in manifest["artifacts"]:
            item["sha256"] = build.digest(directory / item["file"])
            item["size"] = (directory / item["file"]).stat().st_size
        (directory / build.MANIFEST).write_bytes(build.canonical(manifest))
        base = {*build.names(self.ctx).values(), *build.REPORTS, build.MANIFEST}
        (directory / build.SUMS).write_text("".join(f"{build.digest(directory / n)}  {n}\n" for n in sorted(base)))

    def refresh_signing_hashes(self, directory):
        digests = signing.payload(directory / build.names(self.ctx)["play"], directory / signing.signed_name(self.ctx))
        (directory / signing.RECORD).write_bytes(build.canonical(signing.record(directory, self.ctx, self.root, self.expected, digests)))
        (directory / signing.SUMS).write_text(signing.checksums(directory))

    def test_real_signing_and_tag_handoff_preserve_all_unsigned_build_bytes(self):
        encoded = self.env[signing.SECRET_NAMES[0]]
        self.env[signing.SECRET_NAMES[0]] = "\n".join(encoded[i:i + 76] for i in range(0, len(encoded), 76)) + "\n"
        self.fixture.git("tag", "v2.2.3")
        self.env.update(GITHUB_EVENT_NAME="push", GITHUB_REF="refs/tags/v2.2.3")
        self.ctx = signing.signing_context(self.root, self.env)
        self.fixture.ctx = self.ctx
        shutil.rmtree(self.unsigned)
        self.unsigned = self.fixture.collect()
        self.signed()
        self.assertEqual(9, len(list(self.output.iterdir())))
        for original in self.unsigned.iterdir():
            self.assertEqual(original.read_bytes(), (self.output / original.name).read_bytes())
        build.unsigned(self.output / build.names(self.ctx)["foss"])
        signing.verify(self.output, self.ctx, self.root, signing.tool_env(self.env))
        archive = self.fixture.work / "signed.zip"
        with zipfile.ZipFile(archive, "w") as z:
            for p in self.output.iterdir():
                z.write(p, p.name)
        destination = self.fixture.work / "downloaded"
        run = subprocess.run([sys.executable, "-m", "ci.android_play_signing", "unpack-signed", "--root", str(self.root),
                              "--archive", str(archive), "--digest", build.digest(archive), "--output", str(destination)],
                             cwd=ROOT, env=signing.tool_env(self.env), capture_output=True, text=True, timeout=60)
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertEqual((self.output / signing.RECORD).read_bytes(), (destination / signing.RECORD).read_bytes())

    def test_prs_private_repositories_and_unreviewed_refs_cannot_authorize_signing(self):
        for update in ({"GITHUB_EVENT_NAME": "pull_request", "GITHUB_REF": "refs/pull/4/merge"},
                       {"GITHUB_REPOSITORY": "lavasecurity/lavasec-android-internal"},
                       {"GITHUB_REF": "refs/heads/feature"}):
            with self.subTest(update=update), self.assertRaises(ValueError):
                signing.signing_context(self.root, dict(self.env, **update))
        with self.assertRaises(ValueError):
            signing.sign(self.unsigned, self.output, dict(self.ctx, mode="validation"), self.root, self.env)
        self.assertFalse(self.output.exists())

    def test_missing_wrong_and_unreadable_keys_fail_without_retaining_signing_material(self):
        for update in ({signing.SECRET_NAMES[0]: ""}, {signing.SECRET_NAMES[0]: "not base64!"},
                       {signing.SECRET_NAMES[0]: base64.b64encode(self.key.read_bytes() + b"wrong key").decode()},
                       {signing.SECRET_NAMES[1]: "incorrect password"}, {signing.SECRET_NAMES[2]: "incorrect key password"}):
            with self.subTest(secret_names=list(update)), self.assertRaises(ValueError):
                signing.sign(self.unsigned, self.output, self.ctx, self.root, dict(self.env, **update))
            self.assertFalse(self.output.exists())
            self.assertFalse(list(self.fixture.work.glob("lavasec-play-signing-*")))
        with patch.object(signing, "policy", return_value=dict(self.expected, certificateSha256="0" * 64)), self.assertRaisesRegex(ValueError, "certificate"):
            signing.sign(self.unsigned, self.output, self.ctx, self.root, self.env)
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.fixture.work.glob("lavasec-play-signing-*")))

    def test_secrets_never_enter_command_arguments_verifier_environment_or_retained_files(self):
        calls = []
        actual = signing.run_tool
        def capture(command, env):
            calls.append((command, env))
            return actual(command, env)
        with patch.object(signing, "run_tool", side_effect=capture):
            self.signed()
        for command, env in calls:
            self.assertNotIn(self.password, command)
            self.assertNotIn(self.env[signing.SECRET_NAMES[0]], command)
            self.assertNotIn(signing.SECRET_NAMES[0], env)
            if command[0] == "java":
                self.assertTrue(all(name not in env for name in signing.SECRET_NAMES))
        self.assertFalse(list(self.fixture.work.glob("lavasec-play-signing-*")))
        for p in self.output.iterdir():
            self.assertNotIn(self.password.encode(), p.read_bytes())
            self.assertNotIn(self.key.read_bytes(), p.read_bytes())

    def test_changed_payload_is_rejected_even_with_updated_receipt_hashes(self):
        self.signed()
        self.rewrite_zip(self.output / signing.signed_name(self.ctx), {"base/assets/index.android.bundle": b"changed executable content"})
        with self.assertRaisesRegex(ValueError, "payload"):
            signing.verify(self.output, self.ctx, self.root, self.env)

    def test_forged_unsigned_inputs_and_consistent_hashes_cannot_bypass_crypto_verification(self):
        self.signed()
        for name in (build.names(self.ctx)["play"], signing.signed_name(self.ctx)):
            self.rewrite_zip(self.output / name, {"base/assets/index.android.bundle": b"forged content with self-consistent hashes"})
        self.refresh_base_hashes(self.output)
        self.refresh_signing_hashes(self.output)
        build.verify(self.output, self.ctx, self.root, extra_files=signing.extra_files(self.ctx))
        with self.assertRaisesRegex(ValueError, "java rejected"):
            signing.verify(self.output, self.ctx, self.root, self.env)

    def test_unsigned_replacement_wrong_certificate_and_signed_foss_are_rejected(self):
        self.signed()
        path = self.output / signing.signed_name(self.ctx)
        saved = path.read_bytes()
        shutil.copyfile(self.output / build.names(self.ctx)["play"], path)
        with self.assertRaises(ValueError):
            signing.verify(self.output, self.ctx, self.root, self.env)
        path.write_bytes(saved)
        with patch.object(signing, "policy", return_value=dict(self.expected, certificateSha256="0" * 64)), self.assertRaisesRegex(ValueError, "java rejected"):
            signing.verify(self.output, self.ctx, self.root, self.env)
        self.rewrite_zip(self.output / build.names(self.ctx)["foss"], {"META-INF/CERT.RSA": b"unexpected FOSS signature"})
        self.refresh_base_hashes(self.output)
        with self.assertRaisesRegex(ValueError, "unsigned"):
            signing.verify(self.output, self.ctx, self.root, self.env)

    def test_record_checksum_and_extra_file_tampering_is_rejected(self):
        self.signed()
        record = self.output / signing.RECORD
        saved = record.read_bytes()
        record.write_bytes(saved.replace(self.ctx["sourceSha"].encode(), b"f" * 40))
        with self.assertRaisesRegex(ValueError, "record"):
            signing.verify(self.output, self.ctx, self.root, self.env)
        record.write_bytes(saved)
        sums = self.output / signing.SUMS
        saved_sums = sums.read_bytes()
        sums.write_bytes(saved_sums + b"extra\n")
        with self.assertRaisesRegex(ValueError, "checksum"):
            signing.verify(self.output, self.ctx, self.root, self.env)
        sums.write_bytes(saved_sums)
        (self.output / "upload.jks").write_bytes(self.key.read_bytes())
        with self.assertRaisesRegex(ValueError, "inventory"):
            signing.verify(self.output, self.ctx, self.root, self.env)


if __name__ == "__main__":
    unittest.main()
