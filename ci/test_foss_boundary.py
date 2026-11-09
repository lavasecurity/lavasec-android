import tempfile
import unittest
from unittest.mock import patch
import zipfile
from pathlib import Path

from ci.test_android_bundle import dex
from ci.verify_foss_boundary import PACKAGED_MANIFEST, audit, verify_dependencies, verify_manifest

GRAPH = b"fossReleaseRuntimeClasspath - Runtime classpath\n+--- org.example:floss:1.0\n"
MANIFEST = b'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.lavasec.app.foss"><application /></manifest>'


class FossBoundaryTest(unittest.TestCase):
    def test_missing_wrong_and_unresolved_graphs_fail_closed(self):
        for value in (b"", b"playReleaseRuntimeClasspath - graph\n+--- org:floss:1\n",
                      GRAPH.replace(b"1.0", b"1.0 FAILED")):
            with self.assertRaises(ValueError):
                verify_dependencies(value)

    def test_direct_and_transitive_proprietary_integrations_are_rejected(self):
        for coordinate in (b"com.android.billingclient:billing:9.1.0", b"com.google.android.gms:play-services-fido:21.0.0",
                           b"androidx.credentials:credentials-play-services-auth:1.6.0"):
            with self.assertRaises(ValueError):
                verify_dependencies(GRAPH + b"|    \\--- " + coordinate + b"\n")

    def test_account_ingress_and_wrong_package_are_rejected(self):
        for value in (MANIFEST.replace(b"com.lavasec.app.foss", b"com.lavasec.app"),
                      MANIFEST.replace(b"<application />", b'<application><activity android:name=".account.AppleOAuthCallbackActivity" /></application>')):
            with self.assertRaises(ValueError):
                verify_manifest(value)

    def test_artifact_dex_is_checked_independently_of_clean_graph(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            graph, manifest, bundle = root / "graph.txt", root / "manifest.xml", root / "app.aab"
            graph.write_bytes(GRAPH)
            manifest.write_bytes(MANIFEST)
            for sdk, rejected in (("Lorg/example/Floss;", False), ("Lcom/google/android/gms/fido/Fido;", True)):
                with zipfile.ZipFile(bundle, "w") as archive:
                    archive.writestr("base/dex/classes.dex", dex([sdk]))
                    archive.writestr(PACKAGED_MANIFEST, b"placeholder protobuf for mocked decoder")
                if rejected:
                    with self.assertRaisesRegex(ValueError, "FOSS DEX"):
                        audit(bundle, graph, root / "bundletool.jar")
                else:
                    with patch("ci.verify_foss_boundary.dump_packaged_manifest", return_value=MANIFEST):
                        receipt = audit(bundle, graph, root / "bundletool.jar")
                    self.assertEqual("com.lavasec.app.foss", receipt["packageName"])
                    self.assertEqual(64, len(receipt["bundleSha256"]))

    def test_missing_manifest_or_wrong_packaged_identity_cannot_use_clean_sidecar(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            graph, bundle = root / "graph.txt", root / "app.aab"
            graph.write_bytes(GRAPH)
            for manifest_present in (False, True):
                with zipfile.ZipFile(bundle, "w") as archive:
                    archive.writestr("base/dex/classes.dex", dex(["Lorg/example/Floss;"]))
                    if manifest_present:
                        archive.writestr(PACKAGED_MANIFEST, b"placeholder protobuf")
                with patch("ci.verify_foss_boundary.dump_packaged_manifest", return_value=MANIFEST.replace(b"com.lavasec.app.foss", b"com.lavasec.app")):
                    with self.assertRaises(ValueError):
                        audit(bundle, graph, root / "bundletool.jar")
