#!/usr/bin/env python3
"""Disposable emulator fixture: receive exact canary UDP53, never reply or forward.

10.0.2.2 aliases the host loopback interface. Host-observed UDP source tuples are
after emulator NAT; they must never be presented as Android's pre-NAT socket tuple.
Only the CLI owns the fixed loopback UDP53 bind. Unit fixtures may supply their own
unprivileged socket to the private receiver; there is no CLI destination override.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import secrets
import signal
import socket
import socketserver
import threading
import time


def canary_nonce(payload):
    prefix = bytes.fromhex("1ea4010000010000000000000a")
    suffix = b"\x0bleak-canary\x07lavasec\x07invalid\x00\x00\x01\x00\x01"
    if len(payload) != len(prefix) + 10 + len(suffix) or not payload.startswith(prefix):
        return None
    if payload[len(prefix) + 10:] != suffix:
        return None
    value = payload[len(prefix):len(prefix) + 10].decode("ascii", errors="replace")
    return value if re.fullmatch(r"[0-9a-f]{10}", value) else None


class PeerState:
    def __init__(self, token):
        self.token = token
        self.condition = threading.Condition()
        self.records = []
        self.windows = {}
        self.overflow = False
        self.failure = None
        self.stop = threading.Event()

    def observe(self, payload, source, destination):
        nonce = canary_nonce(payload)
        if nonce is None:
            # Do not retain unrelated bytes, but never turn an unrecognized real
            # datagram into a trustworthy no-send observation on this dedicated peer.
            with self.condition:
                self.failure = "unrecognized datagram at dedicated UDP53 peer"
                self.stop.set()
                self.condition.notify_all()
            return None
        with self.condition:
            if len(self.records) >= 1024:
                self.overflow = True
                self.condition.notify_all()
                return None
            record = {"sequence": len(self.records) + 1, "nonce": nonce,
                      "payloadBase64": base64.b64encode(payload).decode("ascii"),
                      "hostSourceAddress": source[0], "hostSourcePort": source[1],
                      "hostDestinationAddress": destination[0], "hostDestinationPort": destination[1],
                      "tupleScope": "host-after-emulator-NAT", "observedMonotonicNs": time.monotonic_ns()}
            self.records.append(record)
            self.condition.notify_all()
            return record

    def request(self, request):
        if not isinstance(request, dict) or not secrets.compare_digest(str(request.get("token", "")), self.token):
            raise ValueError("invalid fixture control token")
        operation = request.get("op")
        if operation == "shutdown":
            self.stop.set()
            return {"stopping": True}
        nonce = request.get("nonce")
        if not isinstance(nonce, str) or not re.fullmatch(r"[0-9a-f]{10}", nonce):
            raise ValueError("invalid canary nonce")
        with self.condition:
            if self.failure is not None or self.stop.is_set():
                raise ValueError("capture peer is unavailable")
            if self.overflow:
                raise ValueError("capture overflow; no negative result is trustworthy")
            if operation == "begin":
                if len(self.windows) >= 64:
                    raise ValueError("too many unfinished capture windows")
                window = secrets.token_hex(16)
                self.windows[window] = (nonce, len(self.records))
                return {"window": window, "cursor": len(self.records)}
            window = request.get("window")
            witness = self.windows.get(window)
            if witness is None or witness[0] != nonce:
                raise ValueError("unknown or mismatched capture window")
            cursor = request.get("cursor")
            if type(cursor) is not int or not witness[1] <= cursor <= len(self.records):
                raise ValueError("invalid capture cursor")
            if operation == "finish":
                del self.windows[window]
                return {"finished": True}
            if operation != "read":
                raise ValueError("unknown fixture operation")
            duration = request.get("waitMillis")
            if type(duration) is not int or not 0 <= duration <= 5000:
                raise ValueError("invalid bounded capture wait")
            deadline = time.monotonic() + duration / 1000
            while len(self.records) <= cursor and not self.overflow and self.failure is None and not self.stop.is_set():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                self.condition.wait(remaining)
            if self.overflow:
                raise ValueError("capture overflow; no negative result is trustworthy")
            if self.failure is not None or self.stop.is_set():
                raise ValueError("capture peer is unavailable")
            # Include a mismatching/rearmed nonce too: an unintended send must not
            # disappear just because the original arm's expected nonce was different.
            return {"packets": self.records[cursor:]}


class _UdpReceiver:
    def __init__(self, sock, state, observations):
        self.sock, self.state, self.observations = sock, state, observations
        self.thread = threading.Thread(target=self.run, daemon=True)

    def run(self):
        self.sock.settimeout(0.2)
        while not self.state.stop.is_set():
            try:
                payload, source = self.sock.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError as error:
                with self.state.condition:
                    self.state.failure = str(error)
                    self.state.stop.set()
                    self.state.condition.notify_all()
                break
            record = self.state.observe(payload, source, self.sock.getsockname())
            if record is not None:
                try:
                    self.observations.write(json.dumps(record, sort_keys=True) + "\n")
                    self.observations.flush()
                except OSError as error:
                    with self.state.condition:
                        self.state.failure = str(error)
                        self.state.stop.set()
                        self.state.condition.notify_all()
                    break


class _ControlHandler(socketserver.StreamRequestHandler):
    def handle(self):
        self.request.settimeout(7)
        try:
            line = self.rfile.readline(4097)
            if len(line) > 4096 or not line.endswith(b"\n"):
                raise ValueError("invalid bounded control frame")
            result = self.server.peer_state.request(json.loads(line))
            result = {"ok": True, "protocol": 1, **result}
        except (ValueError, TypeError, OSError) as error:
            result = {"ok": False, "error": str(error)}
        self.wfile.write(json.dumps(result).encode("utf-8") + b"\n")


class _ControlServer(socketserver.ThreadingTCPServer):
    daemon_threads = True


def write_private_json(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as output:
        json.dump(value, output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ready", type=Path, required=True)
    parser.add_argument("--observations", type=Path, required=True)
    parser.add_argument("--owner-uid", type=int, required=True)
    parser.add_argument("--owner-gid", type=int, required=True)
    parser.add_argument("--lifetime-seconds", type=int, default=2500)
    args = parser.parse_args()
    if not 1 <= args.lifetime_seconds <= 3600 or args.owner_uid <= 0 or args.owner_gid <= 0:
        parser.error("bounded lifetime and an unprivileged output owner are required")
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.bind(("127.0.0.1", 53))
    except OSError as error:
        sock.close()
        parser.exit(1, f"Host cannot bind required loopback UDP53 ({error}); no canary peer was started.\n")
    # CI may authorize only this privileged bind. Drop all privilege before files,
    # control sockets, threads or test requests; never modify emulator security.
    if os.geteuid() == 0:
        os.setgroups([])
        os.setgid(args.owner_gid)
        os.setuid(args.owner_uid)
    elif (os.geteuid(), os.getegid()) != (args.owner_uid, args.owner_gid):
        sock.close()
        parser.error("output owner does not match the unprivileged peer process")
    state = PeerState(secrets.token_hex(32))
    for signum in (signal.SIGTERM, signal.SIGINT):
        signal.signal(signum, lambda *_: state.stop.set())
    with sock, args.observations.open("x") as observations, _ControlServer(("127.0.0.1", 0), _ControlHandler) as control:
        control.peer_state = state
        receiver = _UdpReceiver(sock, state, observations)
        receiver.thread.start()
        serving = threading.Thread(target=control.serve_forever, kwargs={"poll_interval": 0.1}, daemon=True)
        serving.start()
        write_private_json(args.ready, {"protocol": 1, "controlPort": control.server_address[1],
                           "token": state.token, "udpAddress": "127.0.0.1", "udpPort": 53})
        deadline = time.monotonic() + args.lifetime_seconds
        while not state.stop.wait(0.2):
            if time.monotonic() >= deadline:
                state.stop.set()
                break
        control.shutdown()
        serving.join(timeout=2)
        receiver.thread.join(timeout=2)
    return 1 if state.failure is not None else 0


if __name__ == "__main__":
    raise SystemExit(main())
