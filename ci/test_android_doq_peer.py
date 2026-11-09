"""Controlled subprocess/parser contracts only; no Android, TLS or QUIC exchange is qualified."""
import argparse
import contextlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent))
import android_doq_peer as driver

CLASS = "com.lavasec.app.vpn.NativeDoqSocketJourneyInstrumentationTest"
METHOD = "verifiedAndroidQuicPeerRestoresCallerIdAndReleasesExactEpochResources"
SOURCE = "a" * 40
LIMITS = driver.Limits(guard=2, ready=0.8, instrument=2, finish=0.8, cleanup=0.3)


def transcript(status=0, method=METHOD, aborted=False):
    lines = [f"INSTRUMENTATION_STATUS: class={CLASS}", f"INSTRUMENTATION_STATUS: test={method}",
             "INSTRUMENTATION_STATUS_CODE: 1"]
    if not aborted:
        lines += [f"INSTRUMENTATION_STATUS: class={CLASS}", f"INSTRUMENTATION_STATUS: test={method}",
                  f"INSTRUMENTATION_STATUS_CODE: {status}", "OK (1 test)", "INSTRUMENTATION_CODE: -1"]
    return "\n".join(lines) + "\n"


def receipts():
    # Independent exact public query/reply, not copied from the driver's helper.
    query = bytes([0, 37, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 11]) + b"android-doq" + bytes([7]) + b"invalid" + bytes([0, 0, 1, 0, 1])
    answer = bytearray(query); answer[4:6] = bytes.fromhex("8183")
    return [{"event": "query", "alpn": "doq", "sni": "resolver.test", "stream": 0, "query_fin": True,
             "query_wire_hex": query.hex(), "reply_wire_hex": answer.hex(),
             "host_observed_peer": "127.0.0.1:32145", "source_scope": "NAT-translated-host-loopback"},
            {"event": "complete", "accepted_queries": 1, "response_queued_with_fin": True,
             "scope": "host-loopback-NAT-peer"}]


@contextlib.contextmanager
def fixture(peer_mode="success", adb_mode="success"):
    with tempfile.TemporaryDirectory() as scratch:
        root = Path(scratch)
        fixture_path = root / "ThirdParty/doq-core/tests/fixtures"
        fixture_path.mkdir(parents=True)
        cert = fixture_path / "resolver.crt"; cert.write_bytes(b"public synthetic contract certificate")
        key = fixture_path / "resolver.key"; key.write_bytes(b"public synthetic contract key")
        asset = root / "android/vpn/src/androidTest/assets/doq/resolver.crt"
        asset.parent.mkdir(parents=True); asset.write_bytes(cert.read_bytes())
        (root / "source-sha.txt").write_text(SOURCE + "\n")
        ready = {"event": "ready", "address": "127.0.0.1", "port": 49152, "max_duration_ms": 120000}
        if peer_mode == "bad_ready":
            ready["address"] = "0.0.0.0"
        peer = root / "peer"
        peer.write_text(f"#!{sys.executable}\n" + """
import json, os, signal, sys, time
from pathlib import Path
root = Path(__file__).parent
(root / 'peer.pid').write_text(str(os.getpid()))
""" + f"mode = {peer_mode!r}\nreadiness = {ready!r}\nrecords = {receipts()!r}\n" + """
if mode in ('missing_ready', 'ignore_termination'):
    if mode == 'ignore_termination': signal.signal(signal.SIGTERM, signal.SIG_IGN)
    if mode == 'missing_ready': time.sleep(30)
if mode == 'malformed_ready': print('{not-json}', flush=True)
else: print(json.dumps(readiness), flush=True)
if mode in ('bad_ready', 'malformed_ready'): sys.exit(0)
limit = time.monotonic() + 30
while not (root / 'instrument.called').exists():
    if time.monotonic() > limit: sys.exit(8)
    time.sleep(0.005)
if mode == 'bad_query': records[0]['sni'] = 'wrong.test'
print(json.dumps(records[0]), flush=True)
if mode == 'stderr_failure':
    sys.stderr.write('android-doq-peer: QUIC send: CryptoFail\\n'); sys.stderr.flush()
    sys.exit(7)
if mode in ('peer_timeout', 'ignore_termination'): time.sleep(30)
print(json.dumps(records[1]), flush=True)
sys.exit(7 if mode == 'peer_nonzero' else 0)
""")
        peer.chmod(0o755)
        adb = root / "adb"
        output = transcript(status=-2 if adb_mode == "native_fail" else -3 if adb_mode == "native_skip" else 0,
                            method="substitutedMethod" if adb_mode == "substitution" else METHOD,
                            aborted=adb_mode in ("native_abort", "adb_timeout"))
        adb.write_text(f"#!{sys.executable}\n" + """
import json, os, sys, time
from pathlib import Path
root = Path(__file__).parent
args = sys.argv[1:]
with (root / 'adb.commands').open('a') as stream: stream.write(json.dumps(args) + '\\n')
""" + f"mode = {adb_mode!r}\noutput = {output!r}\n" + """
if args[-3:] == ['shell', 'getprop', 'ro.kernel.qemu']:
    print('0' if mode == 'physical' else '1'); sys.exit(0)
assert '-w' in args and '-r' in args, 'the real parser requires raw instrumentation status bundles'
(root / 'adb.pid').write_text(str(os.getpid()))
(root / 'instrument.called').touch()
print(output, end='', flush=True)
if mode == 'adb_timeout': time.sleep(30)
sys.exit(9 if mode == 'adb_nonzero' else 0)
""")
        adb.chmod(0o755)
        args = argparse.Namespace(peer=peer, cert=cert, key=key, adb=adb,
                                  serial="emulator-5554", evidence=root / "evidence")
        # Only source/fixtures are controlled. The child lifetime, pipes, exit and raw files
        # below are actual OS subprocesses; their JSON/transcript is explicitly synthetic.
        with patch.object(driver, "ROOT", root), patch.object(driver, "source_sha", return_value=SOURCE):
            yield args


class AndroidDoqDriverContracts(unittest.TestCase):
    def assert_reaped(self, path):
        if path.exists():
            with self.assertRaises(ProcessLookupError):
                os.kill(int(path.read_text()), 0)

    def assert_failed_evidence(self, args):
        metadata = json.loads((args.evidence / "doq-peer-evidence.json").read_text())
        self.assertEqual("fail", metadata["status"])
        cases = list(ET.parse(args.evidence / "doq.xml").iter("testcase"))
        self.assertTrue(any(case.get("classname") == "runner" and case.find("error") is not None for case in cases))
        self.assert_reaped(args.peer.parent / "peer.pid")
        self.assert_reaped(args.peer.parent / "adb.pid")
        return metadata

    def test_success_demands_source_exact_native_and_host_receipts_with_no_reset_or_extra_method(self):
        with fixture() as args:
            result = driver.run_fixture(args, LIMITS)
            self.assertEqual("pass", result["status"])
            self.assertEqual(SOURCE, result["source_sha"])
            self.assertEqual(driver.sha256(args.peer), result["peer_sha256"])
            self.assertEqual(driver.sha256(args.cert), result["certificate_sha256"])
            self.assertEqual(0, result["adb_exit"]); self.assertEqual(0, result["peer_exit"])
            commands = [json.loads(line) for line in (args.adb.parent / "adb.commands").read_text().splitlines()]
            self.assertEqual([["-s", "emulator-5554", "shell", "getprop", "ro.kernel.qemu"],
                ["-s", "emulator-5554", "shell", "am", "instrument", "-w", "-r", "-e", "class", f"{CLASS}#{METHOD}",
                 "-e", "doqPeerHost", "10.0.2.2", "-e", "doqPeerPort", "49152",
                 "com.lavasec.app.vpn.test/androidx.test.runner.AndroidJUnitRunner"]], commands)
            self.assertEqual(1, len(list(ET.parse(args.evidence / "doq.xml").iter("testcase"))))
            self.assertEqual(["ready", "query", "complete"], [json.loads(line)["event"] for line in
                (args.evidence / "doq-peer.txt").read_text().splitlines()])
            self.assert_reaped(args.peer.parent / "peer.pid")

    def test_missing_or_malformed_readiness_never_admits_instrumentation_and_reaps_its_peer(self):
        for mode in ("missing_ready", "bad_ready", "malformed_ready"):
            with self.subTest(mode=mode), fixture(peer_mode=mode) as args:
                with self.assertRaises((ValueError, TimeoutError)):
                    driver.run_fixture(args, LIMITS)
                self.assert_failed_evidence(args)
                self.assertFalse((args.peer.parent / "instrument.called").exists())

    def test_unconfirmed_emulator_and_mismatching_source_refuse_before_peer_launch(self):
        for mode in ("physical", "source"):
            with self.subTest(mode=mode), fixture(adb_mode="physical" if mode == "physical" else "success") as args:
                if mode == "source": (args.peer.parent / "source-sha.txt").write_text("b" * 40)
                with self.assertRaises(ValueError): driver.run_fixture(args, LIMITS)
                self.assert_failed_evidence(args)
                self.assertFalse((args.peer.parent / "peer.pid").exists())
                self.assertFalse((args.peer.parent / "instrument.called").exists())

    def test_failed_skipped_aborted_or_substituted_native_result_and_nonzero_adb_never_pass(self):
        for mode in ("native_fail", "native_skip", "native_abort", "substitution", "adb_nonzero", "adb_timeout"):
            with self.subTest(mode=mode), fixture(adb_mode=mode) as args:
                with self.assertRaises((ValueError, TimeoutError)): driver.run_fixture(args, LIMITS)
                self.assert_failed_evidence(args)
                self.assertIn("INSTRUMENTATION_STATUS", (args.evidence / "doq.txt").read_text())

    def test_nonzero_incomplete_wrong_host_or_term_resistant_peer_cannot_promote_native_pass(self):
        for mode in ("peer_nonzero", "peer_timeout", "bad_query", "ignore_termination"):
            with self.subTest(mode=mode), fixture(peer_mode=mode) as args:
                with self.assertRaises((ValueError, TimeoutError)): driver.run_fixture(args, LIMITS)
                self.assert_failed_evidence(args)
                self.assertIn("OK (1 test)", (args.evidence / "doq.txt").read_text())

    def test_primary_native_failure_is_retained_and_cleanup_never_targets_an_unrelated_process(self):
        unrelated = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        try:
            with fixture(peer_mode="ignore_termination", adb_mode="native_fail") as args:
                with self.assertRaisesRegex(ValueError, "one executed terminal pass"):
                    driver.run_fixture(args, LIMITS)
                metadata = self.assert_failed_evidence(args)
                self.assertIn("one executed terminal pass", metadata["error"])
                self.assertIsNone(unrelated.poll())
        finally:
            unrelated.terminate(); unrelated.wait(timeout=2)

    def test_peer_crypto_diagnostic_is_causal_and_missing_completion_cannot_promote_native_pass(self):
        with fixture(peer_mode="stderr_failure") as args:
            with self.assertRaisesRegex(ValueError, "host peer exited 7: android-doq-peer: QUIC send: CryptoFail"):
                driver.run_fixture(args, LIMITS)
            metadata = self.assert_failed_evidence(args)
            self.assertEqual(0, metadata["adb_exit"])
            self.assertEqual(7, metadata["peer_exit"])
            self.assertEqual("android-doq-peer: QUIC send: CryptoFail", metadata["peer_diagnostic"])
            self.assertIn("OK (1 test)", (args.evidence / "doq.txt").read_text())
            self.assertEqual(["ready", "query"], [json.loads(line)["event"] for line in
                             (args.evidence / "doq-peer.txt").read_text().splitlines()])
            self.assertEqual("android-doq-peer: QUIC send: CryptoFail\n",
                             (args.evidence / "doq-peer-stderr.txt").read_text())
            self.assertNotIn("Expecting value", metadata["error"])

    def test_reused_evidence_is_not_overwritten(self):
        with fixture() as args:
            args.evidence.mkdir(); (args.evidence / "doq.txt").write_text("retained failed invocation")
            with self.assertRaisesRegex(ValueError, "reused or overwritten"):
                driver.run_fixture(args, LIMITS)
            self.assertEqual("retained failed invocation", (args.evidence / "doq.txt").read_text())

    def test_readiness_types_duplicates_and_terminal_tuple_framing_are_fail_closed(self):
        good = {"event": "ready", "address": "127.0.0.1", "port": 49152, "max_duration_ms": 120000}
        for change in ({"port": True}, {"port": 53}, {"port": "49152"}, {"max_duration_ms": 120001}, {"extra": 1}):
            with self.subTest(change=change), self.assertRaises(ValueError): driver.ready({**good, **change})
        with self.assertRaises(ValueError): json.loads('{"port":1,"port":2}', object_pairs_hook=driver.unique_object)
        for change in ({"stream": True}, {"host_observed_peer": "192.168.1.1:32145"}, {"query_fin": False},
                       {"query_wire_hex": "0000"}, {"reply_wire_hex": "0000"}):
            rows = receipts(); rows[0].update(change)
            with self.subTest(change=change), self.assertRaises(ValueError): driver.host_pass(rows)
        rows = receipts(); rows[1]["accepted_queries"] = True
        with self.assertRaises(ValueError): driver.host_pass(rows)

    def test_duplicate_passes_or_empty_success_summary_do_not_replace_one_exact_native_terminal(self):
        for text in (transcript() + transcript(), "OK (0 tests)\nINSTRUMENTATION_CODE: -1\n"):
            with self.subTest(text=text), self.assertRaises(ValueError): driver.native_pass(text)


if __name__ == "__main__":
    unittest.main()
