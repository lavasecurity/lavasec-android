"""Host fixture protocol tests; these do not bind privileged UDP53 or qualify Android."""
import base64
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import socket
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/android-canary-udp-peer.py"
SPEC = importlib.util.spec_from_file_location("android_canary_udp_peer", SCRIPT)
peer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(peer)


def query(nonce="a01b02c03d"):
    # Independent exact DNS fixture, not a response or production emitter substitute.
    return bytes.fromhex("1ea401000001000000000000") + b"\x0a" + nonce.encode("ascii") + (
        b"\x0bleak-canary\x07lavasec\x07invalid\x00\x00\x01\x00\x01")


class CanaryPeerProtocolTests(unittest.TestCase):
    def setUp(self):
        self.state = peer.PeerState("private-fixture-token")
        self.nonce = "a01b02c03d"

    def request(self, operation, **fields):
        return self.state.request({"token": self.state.token, "op": operation,
                                   "nonce": self.nonce, **fields})

    def test_exact_query_only_never_accepts_replies_other_dns_or_malformed_frames(self):
        good = query()
        self.assertEqual(self.nonce, peer.canary_nonce(good))
        malformed = [good[:-1], good + b"\0", b"\x1e\xa4\x81\x80" + good[4:],
                     good[:2] + b"\x00\x00" + good[4:], query("A01B02C03D"),
                     good.replace(b"invalid", b"example"), good[:11] + b"\x01" + good[12:]]
        for payload in malformed:
            with self.subTest(payload=payload):
                self.assertIsNone(peer.canary_nonce(payload))
                self.assertIsNone(self.state.observe(payload, ("127.0.0.1", 1234), ("127.0.0.1", 53)))
        self.assertEqual([], self.state.records)
        self.assertIsNotNone(self.state.failure, "Unrecognized traffic must not manufacture an empty capture verdict")

    def test_malformed_real_datagram_invalidates_an_open_negative_window_without_retaining_bytes(self):
        window = self.request("begin")
        self.state.observe(query()[:-1], ("127.0.0.1", 1234), ("127.0.0.1", 53))
        self.assertEqual([], self.state.records)
        with self.assertRaises(ValueError):
            self.request("read", **window, waitMillis=0)

    def test_windows_exclude_old_packets_but_include_wrong_nonce_and_keep_nat_tuple_explicit(self):
        self.state.observe(query(), ("127.0.0.1", 1234), ("127.0.0.1", 53))
        window = self.request("begin")
        self.assertEqual([], self.request("read", **window, waitMillis=0)["packets"])
        changed = "a01b02c03e"
        record = self.state.observe(query(changed), ("127.0.0.1", 5678), ("127.0.0.1", 53))
        packets = self.request("read", **window, waitMillis=0)["packets"]
        self.assertEqual([record], packets)
        self.assertEqual(changed, packets[0]["nonce"], "An unintended rearmed send must not vanish from a negative window")
        self.assertEqual("host-after-emulator-NAT", record["tupleScope"])
        self.assertEqual(("127.0.0.1", 5678), (record["hostSourceAddress"], record["hostSourcePort"]))
        self.assertEqual(("127.0.0.1", 53), (record["hostDestinationAddress"], record["hostDestinationPort"]))
        self.assertEqual(query(changed), base64.b64decode(record["payloadBase64"]))
        self.assertNotIn("androidLocalAddress", record)
        self.assertTrue(self.request("finish", **window)["finished"])
        with self.assertRaises(ValueError):
            self.request("read", **window, waitMillis=0)

    def test_unauthenticated_stale_cursor_nonce_and_unbounded_wait_refuse(self):
        with self.assertRaises(ValueError):
            self.state.request({"token": "wrong", "op": "begin", "nonce": self.nonce})
        self.assertEqual({}, self.state.windows)
        window = self.request("begin")
        for fields in ({"cursor": -1}, {"cursor": 1}, {"cursor": True},
                       {"waitMillis": 5001}, {"waitMillis": -1}, {"window": "stale"},
                       {"nonce": "a01b02c03e"}):
            with self.subTest(fields=fields), self.assertRaises(ValueError):
                self.request("read", **{**window, "waitMillis": 0, **fields})

    def test_overflow_or_receiver_failure_cannot_fabricate_no_datagram(self):
        for failure in ("overflow", "receiver"):
            state = peer.PeerState("private")
            window = state.request({"token": "private", "op": "begin", "nonce": self.nonce})
            if failure == "overflow":
                state.overflow = True
            else:
                state.failure = "controlled socket failure"
            with self.subTest(failure=failure), self.assertRaises(ValueError):
                state.request({"token": "private", "op": "read", "nonce": self.nonce,
                               **window, "waitMillis": 0})

    def test_real_unprivileged_udp_capture_has_exact_bytes_and_no_reply(self):
        # Fixture-only injected ephemeral socket; production CLI has no port override.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as receiver, socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sender:
            receiver.bind(("127.0.0.1", 0))
            sender.bind(("127.0.0.1", 0))
            observations = io.StringIO()
            worker = peer._UdpReceiver(receiver, self.state, observations)
            window = self.request("begin")
            worker.thread.start()
            try:
                sender.sendto(query(), receiver.getsockname())
                packets = self.request("read", **window, waitMillis=1000)["packets"]
                self.assertEqual(1, len(packets))
                self.assertEqual(query(), base64.b64decode(packets[0]["payloadBase64"]))
                self.assertEqual(sender.getsockname()[1], packets[0]["hostSourcePort"])
                self.assertEqual(receiver.getsockname()[1], packets[0]["hostDestinationPort"])
                sender.settimeout(0.1)
                with self.assertRaises(socket.timeout):
                    sender.recvfrom(4096)
            finally:
                self.state.stop.set()
                worker.thread.join(timeout=2)
            self.assertFalse(worker.thread.is_alive())
            self.assertEqual(packets, [json.loads(line) for line in observations.getvalue().splitlines()])

    def test_real_control_socket_requires_token_and_returns_actual_capture(self):
        with peer._ControlServer(("127.0.0.1", 0), peer._ControlHandler) as server:
            server.peer_state = self.state
            serving = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01})
            serving.start()
            try:
                def send(request):
                    with socket.create_connection(server.server_address, timeout=2) as control:
                        control.sendall(json.dumps(request).encode() + b"\n")
                        return json.loads(control.makefile("rb").readline())
                refused = send({"token": "wrong", "op": "begin", "nonce": self.nonce})
                self.assertFalse(refused["ok"])
                window = send({"token": self.state.token, "op": "begin", "nonce": self.nonce})
                self.assertTrue(window["ok"])
                self.state.observe(query(), ("127.0.0.1", 1234), ("127.0.0.1", 53))
                result = send({"token": self.state.token, "op": "read", "nonce": self.nonce,
                               "window": window["window"], "cursor": window["cursor"], "waitMillis": 0})
                self.assertTrue(result["ok"])
                self.assertEqual(query(), base64.b64decode(result["packets"][0]["payloadBase64"]))
                self.assertNotIn("private-fixture-token", json.dumps(result))
            finally:
                server.shutdown(); serving.join(timeout=2)

    def test_cli_bind_refusal_is_explicit_and_cannot_change_udp53_destination(self):
        with tempfile.TemporaryDirectory() as scratch:
            ready = Path(scratch) / "ready.json"
            observations = Path(scratch) / "observations.jsonl"
            argv = [str(SCRIPT), "--ready", str(ready), "--observations", str(observations),
                    "--owner-uid", "1001", "--owner-gid", "1001"]
            refused = Mock()
            refused.bind.side_effect = PermissionError("controlled EACCES")
            error = io.StringIO()
            with patch.object(sys, "argv", argv), patch.object(peer.socket, "socket", return_value=refused), contextlib.redirect_stderr(error):
                with self.assertRaises(SystemExit) as result:
                    peer.main()
            self.assertEqual(1, result.exception.code)
            refused.bind.assert_called_once_with(("127.0.0.1", 53))
            refused.close.assert_called_once()
            self.assertIn("required loopback UDP53", error.getvalue())
            self.assertFalse(ready.exists()); self.assertFalse(observations.exists())
            with patch.object(sys, "argv", argv + ["--udp-port", "5353"]), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as result:
                    peer.main()
            self.assertEqual(2, result.exception.code, "There must be no production fixture CLI port override")


if __name__ == "__main__":
    unittest.main()
