#!/usr/bin/env python3
"""Opt-in, owned-emulator InputReader transport; never a TalkBack qualification.

Root runs this with its SDK-generated protobuf environment, then owns adb reverse
and passes the private ready-file port/nonce as talkbackBridgePort/Nonce. Android
uses a bounded raw loopback Socket HTTP request, not production cleartext policy.
Only POST /v1/preflight, /v1/gesture and /v1/cancel exist. See validate_request for
the exact v1 body. Native PID/owner/window/source fields are correlation, NOT host
proof of app authority: the fixture must fence its actual focused native window,
product and secure-setting ownership before/after each operation independently.

The sanitized public SDK discovery JSON contains only grpc.port, grpc.jwks,
grpc.jwk_active, grpc.allowlist and actual_loopback_binding. No existing token or
emulator_access.json contents are read. RSA/JWT material stays in process memory.
Every sendTouch ACK is only scheduling; an exact subsequent kernel SYN frame is
required. There is no accepted-operation replay, arbitrary RPC or client ADB.
"""

import argparse
from collections import deque
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import signal
import stat
import subprocess
import sys
import threading
import time
import uuid

# Must precede any import of grpc (including generated modules).
os.environ["GRPC_ENABLE_FORK_SUPPORT"] = "1"
os.environ["GRPC_POLL_STRATEGY"] = "poll"

EVENT_PATH = "/dev/input/event1"
TOUCH_ID = 1101
AUDIENCES = ["/android.emulation.control.EmulatorController/getStatus",
             "/android.emulation.control.EmulatorController/sendTouch"]
BODY_LIMIT = 12_288
MAX_FRAMES = 32
REQUEST_KEYS = {"nonce", "request_id", "sequence", "stage", "pid", "owner", "graph",
                "window", "source", "display", "app_rect", "frames"}


class Refusal(Exception):
    """A fixed public reason, never SDK diagnostics containing tokens."""


def require(condition, code):
    if not condition:
        raise Refusal(code)


def integer(value, low, high):
    return type(value) is int and low <= value <= high


def unique_json(text):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "DUPLICATE_FIELD")
            result[key] = value
        return result
    try:
        return json.loads(text, object_pairs_hook=pairs,
                          parse_constant=lambda _: (_ for _ in ()).throw(Refusal("BAD_JSON")))
    except (ValueError, UnicodeError):
        raise Refusal("BAD_JSON") from None


def event_hub(text):
    """Select only the actual primary EventHub device, never another MT slot."""
    require(len(text) <= 2_000_000, "CLASSIFICATION_TOO_LARGE")
    hub_text, separator, reader_text = text.partition("Input Reader State")
    require(separator, "NO_INPUT_READER")
    hub_blocks = re.split(r"(?m)^\s+(?=\d+: )", hub_text)
    hubs = [block for block in hub_blocks if re.search(
        r"(?m)^\s*Path: " + re.escape(EVENT_PATH) + r"\s*$", block)]
    require(len(hubs) == 1, "INPUT_PATH_AMBIGUOUS")
    return hubs[0], reader_text


def parse_classification(text):
    """Join the actual EventHub path to its InputReader DIRECT mapper/viewport."""
    hub, reader_text = event_hub(text)
    identity = re.search(r"^\s*(\d+): ([^\n]+)", hub)
    require(identity is not None and "Enabled: true" in hub, "INPUT_DISABLED")
    eventhub_id, name = int(identity[1]), identity[2].strip()
    require(name == "virtio_input_multi_touch_1" and "TOUCH_MT" in hub,
            "NOT_OWNED_PROTOCOL_B_DEVICE")
    require(re.search(r"ABS_MT_TRACKING_ID=-1(?:,|\s|$)", hub), "EXISTING_CONTACT")
    blocks = re.split(r"(?m)^\s*(?=Device \d+: )", reader_text)
    readers = [block for block in blocks if re.search(
        r"EventHub Devices: \[\s*" + str(eventhub_id) + r"\s*\]", block)]
    require(len(readers) == 1, "READER_AMBIGUOUS")
    reader = readers[0]
    identity = re.search(r"^\s*Device (\d+): ([^\n]+)", reader)
    require(identity is not None and identity[2].strip() == name, "READER_IDENTITY")
    sources = re.search(r"(?m)^\s*Sources: ([^\n]+)", reader)
    require(sources is not None, "NO_TOUCHSCREEN_SOURCE")
    source_names = [v.strip() for v in sources[1].split("|")]
    require("TOUCHSCREEN" in source_names and
            "Touch Input Mapper (mode - DIRECT):" in reader and
            re.search(r"DeviceType: TOUCH_SCREEN\s", reader), "NOT_DIRECT_TOUCHSCREEN")
    viewport = re.search(
        r"Viewport INTERNAL: displayId=0, [^\n]*orientation=0, "
        r"logicalFrame=\[0, 0, (\d+), (\d+)\], "
        r"physicalFrame=\[0, 0, (\d+), (\d+)\], "
        r"deviceSize=\[(\d+), (\d+)\], isActive=\[1\]", reader)
    require(viewport is not None, "UNSUPPORTED_VIEWPORT")
    width, height, physical_width, physical_height, device_width, device_height = map(int, viewport.groups())
    require(32 <= width <= 8192 and 32 <= height <= 8192 and
            (width, height) == (physical_width, physical_height) == (device_width, device_height),
            "VIEWPORT_TRANSFORM_UNSUPPORTED")
    axes = []
    for axis in ("X", "Y"):
        values = re.search(r"(?m)^\s*" + axis + r": min=(\d+), max=(\d+),", reader)
        require(values is not None, "NO_RAW_AXES")
        low, high = map(int, values.groups())
        require(low == 0 and high == 32767, "UNSUPPORTED_RAW_AXES")
        axes.append((low, high))
    return {"event_path": EVENT_PATH, "device_name": name, "eventhub_id": eventhub_id,
            "inputreader_id": int(identity[1]), "sources": source_names, "mode": "DIRECT",
            "device_type": "TOUCH_SCREEN", "display_id": 0, "width": width, "height": height,
            "orientation": 0, "active": True}, axes


def released_absolute_position(text):
    """Fresh EventHub per-slot state; generic getevent -p ABS values are not MT state."""
    hub, _ = event_hub(text)
    lines = re.findall(r"(?m)^\s*AbsState: ([^\n]+)$", hub)
    require(len(lines) == 1, "NO_UNIQUE_ABSOLUTE_STATE")
    fields = {}
    for entry in lines[0].split(","):
        match = re.fullmatch(r"\s*(ABS_[A-Z0-9_]+)=(-?\d+)\s*", entry)
        require(match is not None and match[1] not in fields, "AMBIGUOUS_ABSOLUTE_STATE")
        fields[match[1]] = int(match[2])
    require(all(name in fields for name in ("ABS_MT_POSITION_X", "ABS_MT_POSITION_Y",
                                           "ABS_MT_SLOT", "ABS_MT_TRACKING_ID", "ABS_MT_PRESSURE")),
            "INCOMPLETE_ABSOLUTE_STATE")
    require(fields["ABS_MT_SLOT"] == 0 and fields["ABS_MT_TRACKING_ID"] == -1 and
            fields["ABS_MT_PRESSURE"] == 0, "EXISTING_ABSOLUTE_CONTACT")
    x, y = fields["ABS_MT_POSITION_X"], fields["ABS_MT_POSITION_Y"]
    require(integer(x, 0, 32767) and integer(y, 0, 32767), "ABSOLUTE_STATE_OUT_OF_RANGE")
    return x, y


def seed_absolute_position(monitor, text, classification, axes, cursor):
    # The event reader is already running during this fresh snapshot. No frame,
    # partial frame or fault may intervene; never infer an axis from a request.
    fresh_classification, fresh_axes = parse_classification(text)
    require((fresh_classification, fresh_axes) == (classification, axes), "CLASSIFICATION_CHANGED")
    x, y = released_absolute_position(text)
    with monitor.condition:
        parser = monitor.parser
        require(not monitor.closed and parser.sequence == cursor and parser.records == 0 and not parser.changed and
                parser.fault is None and parser.tracking == -1 and parser.pressure == 0,
                "ABSOLUTE_STATE_RACED")
        require(parser.x is None and parser.y is None, "ABSOLUTE_STATE_ALREADY_OBSERVED")
        # Absolute axes persist after release. Kernel records omit unchanged axes;
        # seed these two actual values only, not tracking/pressure or SYN receipts.
        parser.x, parser.y = x, y


def validate_request(body, nonce, classification, stage):
    require(type(body) is dict and set(body) == REQUEST_KEYS, "BAD_FIELDS")
    require(type(body["nonce"]) is str and hmac.compare_digest(body["nonce"], nonce), "UNAUTHORIZED")
    require(type(body["request_id"]) is str and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", body["request_id"]),
            "BAD_REQUEST_ID")
    require(body["stage"] == stage and integer(body["sequence"], 1, 128), "BAD_SEQUENCE")
    require(integer(body["pid"], 1, 2**31 - 1) and integer(body["owner"], -2**31, 2**31 - 1) and
            integer(body["graph"], 0, 2**63 - 1) and integer(body["window"], 0, 2**31 - 1) and
            integer(body["source"], -2**31, 2**31 - 1), "BAD_CORRELATION")
    require(body["display"] == {"id": 0, "width": classification["width"],
                                "height": classification["height"]} and
            all(type(value) is int for value in body["display"].values()), "VIEWPORT_MISMATCH")
    rect = body["app_rect"]
    require(type(rect) is dict and set(rect) == {"left", "top", "right", "bottom"} and
            all(integer(value, 0, 8192) for value in rect.values()), "BAD_APP_RECT")
    require(0 <= rect["left"] < rect["right"] <= classification["width"] and
            0 <= rect["top"] < rect["bottom"] <= classification["height"], "BAD_APP_RECT")
    frames = body["frames"]
    require(type(frames) is list and 3 <= len(frames) <= MAX_FRAMES, "BAD_FRAMES")
    down, moved, strokes, last = False, False, 0, None
    for index, frame in enumerate(frames):
        require(type(frame) is dict and set(frame) == {"at_ms", "x", "y", "pressure"} and
                all(type(value) is int for value in frame.values()), "BAD_FRAME")
        require(0 <= frame["at_ms"] <= 2000 and (frame["at_ms"] == 0 if index == 0 else
                frame["at_ms"] >= frames[index - 1]["at_ms"] + 8), "BAD_TIMING")
        require(rect["left"] <= frame["x"] < rect["right"] and
                rect["top"] <= frame["y"] < rect["bottom"] and
                frame["pressure"] in (0, 1), "OUT_OF_BOUNDS")
        point = (frame["x"], frame["y"])
        if not down:
            require(frame["pressure"] == 1, "BAD_STROKE")
            strokes += 1
            require(strokes <= 2, "BAD_STROKE")
            down, moved = True, False
        elif frame["pressure"] == 0:
            require(moved and point == last, "BAD_RELEASE")
            down = False
        else:
            require(point != last, "NO_MOVE")
            moved = True
        last = point
    require(not down, "MISSING_RELEASE")
    if stage == "preflight":
        y = (rect["top"] + rect["bottom"]) // 2
        require(frames == [{"at_ms": 0, "x": rect["left"] + 1, "y": y, "pressure": 1},
                           {"at_ms": 80, "x": rect["left"] + 2, "y": y, "pressure": 1},
                           {"at_ms": 160, "x": rect["left"] + 2, "y": y, "pressure": 0}],
                "UNSAFE_PREFLIGHT")
    return strokes


@dataclass(frozen=True)
class KernelFrame:
    sequence: int
    tracking: int
    x: int | None
    y: int | None
    pressure: int | None
    changed: frozenset
    records: int


class KernelParser:
    """Only real EV records count; adb/grpc diagnostics never count as frames."""
    LINE = re.compile(r"^\[\s*\d+\.\d+\]\s+(EV_\w+)\s+(\w+)\s+([0-9a-fA-F]{8})\s*$")

    def __init__(self):
        self.tracking, self.x, self.y, self.pressure = -1, None, None, 0
        self.changed, self.records, self.sequence = set(), 0, 0
        self.fault = None

    def feed(self, line):
        match = self.LINE.fullmatch(line.rstrip("\n"))
        if not match:
            if "EV_" in line:
                self.fault = "MALFORMED_KERNEL_RECORD"
            return None
        event, code, raw = match.groups()
        value = int(raw, 16)
        self.records += 1
        if event == "EV_ABS":
            if code == "ABS_MT_SLOT":
                if value != 0:
                    self.fault = "OTHER_KERNEL_CONTACT"
            elif code == "ABS_MT_TRACKING_ID":
                new = -1 if value == 0xFFFFFFFF else value
                if new >= 0 and self.tracking >= 0:
                    self.fault = "OTHER_KERNEL_CONTACT"
                self.tracking = new
                self.changed.add("tracking")
            elif code in {"ABS_MT_POSITION_X", "ABS_MT_POSITION_Y", "ABS_MT_PRESSURE"}:
                field = {"ABS_MT_POSITION_X": "x", "ABS_MT_POSITION_Y": "y",
                         "ABS_MT_PRESSURE": "pressure"}[code]
                setattr(self, field, value)
                self.changed.add(field)
            elif code not in {"ABS_MT_TOUCH_MAJOR", "ABS_MT_TOUCH_MINOR", "ABS_MT_ORIENTATION",
                              "ABS_MT_TOOL_TYPE"}:
                self.fault = "UNEXPECTED_KERNEL_RECORD"
        elif event == "EV_SYN" and code == "SYN_REPORT" and value == 0:
            self.sequence += 1
            frame = KernelFrame(self.sequence, self.tracking, self.x, self.y, self.pressure,
                                frozenset(self.changed), self.records)
            self.changed, self.records = set(), 0
            return frame
        else:
            self.fault = "UNEXPECTED_KERNEL_RECORD"
        return None


class KernelMonitor:
    def __init__(self, process):
        self.process, self.parser = process, KernelParser()
        self.condition = threading.Condition()
        self.frames = deque(maxlen=256)
        self.closed = False
        self.thread = threading.Thread(target=self._read, daemon=True)
        self.thread.start()

    def _read(self):
        try:
            for line in self.process.stdout:
                with self.condition:
                    if len(line) > 2048:
                        self.parser.fault = "KERNEL_RECORD_TOO_LARGE"
                    frame = self.parser.feed(line)
                    if frame:
                        self.frames.append(frame)
                    self.condition.notify_all()
        finally:
            with self.condition:
                self.closed = True
                self.condition.notify_all()

    def cursor(self):
        with self.condition:
            require(not self.closed and self.process.poll() is None, "KERNEL_MONITOR_CLOSED")
            require(self.parser.fault is None and self.parser.tracking == -1 and
                    self.parser.records == 0 and not self.parser.changed, "EXISTING_KERNEL_CONTACT")
            return self.parser.sequence

    def next_frame(self, cursor, deadline):
        with self.condition:
            while True:
                require(self.parser.fault is None, self.parser.fault or "KERNEL_MISMATCH")
                require(not self.closed, "KERNEL_MONITOR_CLOSED")
                remaining = deadline - time.monotonic()
                require(remaining > 0, "KERNEL_TIMEOUT")
                available = [frame for frame in self.frames if frame.sequence > cursor]
                if available:
                    require(len(available) == 1 and available[0].sequence == cursor + 1,
                            "UNOWNED_KERNEL_FRAMES")
                    return available[0]
                self.condition.wait(remaining)

    def stop(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=3)
        self.thread.join(timeout=3)
        require(not self.thread.is_alive(), "KERNEL_MONITOR_NOT_DRAINED")


def match_frame(observed, expected, classification, axes, tracking, down):
    for field, dimension, bounds in (("x", "width", axes[0]), ("y", "height", axes[1])):
        raw = bounds[0] + int(expected[field] * (bounds[1] - bounds[0]) / classification[dimension])
        actual = getattr(observed, field)
        # SDK floating-point normalization may differ by one raw axis unit
        # (under 0.01 display pixel). This is not a pixel/viewport tolerance.
        require(actual is not None and abs(actual - raw) <= 1, "KERNEL_COORDINATE_MISMATCH")
    if expected["pressure"] == 1:
        require(observed.pressure == 1 and observed.tracking >= 0, "KERNEL_DOWN_MISMATCH")
        if not down:
            require({"tracking", "pressure"} <= observed.changed, "MISSING_KERNEL_DOWN")
            tracking = observed.tracking
        else:
            require(observed.tracking == tracking and "tracking" not in observed.changed and
                    bool({"x", "y"} & observed.changed), "KERNEL_MOVE_MISMATCH")
        return tracking, True
    require(down and observed.tracking == -1 and observed.pressure == 0 and
            {"tracking", "pressure"} <= observed.changed, "MISSING_KERNEL_RELEASE")
    return tracking, False


class PublicRegistration:
    def __init__(self, directory, public):
        self.path = directory / (public["kid"] + ".jwk")
        self.data = json.dumps({"keys": [public]}, sort_keys=True, separators=(",", ":")).encode()
        self.complete = False
        fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
        try:
            self.identity = os.fstat(fd)
            with os.fdopen(fd, "wb") as output:
                output.write(self.data)
                output.flush()
                os.fsync(output.fileno())
            self.complete = True
        except BaseException as problem:
            try:
                self.remove()
            except Exception as cleanup:
                problem.cleanup_error = str(cleanup) if isinstance(cleanup, Refusal) else "REGISTRATION_CLEANUP_FAILED"
            raise

    def remove(self):
        try:
            current = self.path.lstat()
        except FileNotFoundError:
            return
        require(stat.S_ISREG(current.st_mode) and current.st_uid == os.getuid() and
                (current.st_dev, current.st_ino) == (self.identity.st_dev, self.identity.st_ino),
                "REGISTRATION_REPLACED")
        fd = os.open(self.path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(fd, "rb") as source:
            opened = os.fstat(source.fileno())
            require((opened.st_dev, opened.st_ino) == (self.identity.st_dev, self.identity.st_ino),
                    "REGISTRATION_REPLACED")
            written = source.read(len(self.data) + 1)
            require(written == self.data if self.complete else self.data.startswith(written),
                    "REGISTRATION_REPLACED")
        current = self.path.lstat()
        require((current.st_dev, current.st_ino) == (self.identity.st_dev, self.identity.st_ino),
                "REGISTRATION_REPLACED")
        self.path.unlink()


class Controller:
    def __init__(self, policy, generated):
        # All owned subprocesses have already started before this import/channel.
        sys.path.insert(0, str(generated))
        import grpc
        import jwt
        from cryptography.hazmat.primitives.asymmetric import rsa
        from google.protobuf.empty_pb2 import Empty
        from jwt.algorithms import RSAAlgorithm
        import emulator_controller_pb2 as pb
        import emulator_controller_pb2_grpc as rpc
        self.grpc, self.jwt, self.Empty, self.pb = grpc, jwt, Empty, pb
        self.registration, self.channel = None, None
        self.key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.kid = "lava-inputreader-" + uuid.uuid4().hex
        self.last_refresh, self.token = 0, None
        try:
            self.channel = grpc.insecure_channel("127.0.0.1:8580")
            self.stub = rpc.EmulatorControllerStub(self.channel)
            try:
                self.stub.getStatus(Empty(), timeout=3)
                raise Refusal("UNAUTHENTICATED_CONTROLLER_ACCEPTED")
            except grpc.RpcError as error:
                require(error.code() in (grpc.StatusCode.UNAUTHENTICATED, grpc.StatusCode.PERMISSION_DENIED),
                        "UNAUTHENTICATED_PROBE_NOT_REFUSED")
            public = RSAAlgorithm.to_jwk(self.key.public_key(), as_dict=True)
            public.update(kid=self.kid, alg="RS256", use="sig")
            self.registration = PublicRegistration(policy["directory"], public)
            deadline = time.monotonic() + 5
            loaded = False
            while time.monotonic() < deadline:
                try:
                    keys = unique_json(policy["active"].read_text())["keys"]
                    loaded = any(key.get("kid") == self.kid and key.get("n") == public["n"] and
                                 key.get("e") == public["e"] for key in keys)
                except (FileNotFoundError, KeyError, Refusal):
                    loaded = False
                if loaded:
                    break
                time.sleep(0.05)
            require(loaded, "OWN_PUBLIC_KEY_NOT_LOADED")
            self.status()
        except BaseException as problem:
            try:
                self.close()
            except Exception as cleanup:
                problem.cleanup_error = str(cleanup) if isinstance(cleanup, Refusal) else "CONTROLLER_CLEANUP_FAILED"
            raise

    def metadata(self):
        now = int(time.time())
        if self.token is None or now - self.last_refresh >= 30:
            self.token = self.jwt.encode({"iss": "gradle-utp-emulator-control", "aud": AUDIENCES,
                                          "iat": now, "nbf": now - 1, "exp": now + 60},
                                         self.key, algorithm="RS256", headers={"kid": self.kid, "typ": None})
            self.last_refresh = now
        return (("authorization", "Bearer " + self.token),)

    def status(self):
        try:
            value = self.stub.getStatus(self.Empty(), timeout=3, metadata=self.metadata())
            require(value.booted, "EMULATOR_NOT_BOOTED")
        except self.grpc.RpcError:
            raise Refusal("STATUS_REFUSED") from None

    def touch(self, frame, timeout=1):
        try:
            self.stub.sendTouch(self.pb.TouchEvent(display=0, touches=[self.pb.Touch(
                x=frame["x"], y=frame["y"], identifier=TOUCH_ID, pressure=frame["pressure"])]),
                timeout=timeout, metadata=self.metadata())
        except self.grpc.RpcError:
            raise Refusal("RPC_REFUSED") from None

    def close(self):
        try:
            if self.registration:
                self.registration.remove()
        finally:
            if self.channel:
                self.channel.close()
            self.token, self.key = None, None


class Bridge:
    def __init__(self, controller, monitor, classification, axes, lifetime):
        self.controller, self.monitor, self.classification, self.axes = controller, monitor, classification, axes
        self.nonce = secrets.token_hex(32)
        self.lock, self.state_lock = threading.Lock(), threading.Lock()
        self.cancelled, self.stopping = threading.Event(), threading.Event()
        self.sequence, self.ids, self.binding, self.preflight = 0, set(), None, False
        self.active, self.last_point, self.finger_may_be_down = None, None, False
        self.expires = time.monotonic() + lifetime

    def operate(self, body, stage):
        strokes = validate_request(body, self.nonce, self.classification, stage)
        require(self.lock.acquire(blocking=False), "BUSY")
        try:
            require(not self.stopping.is_set() and time.monotonic() < self.expires, "EXPIRED")
            require(body["sequence"] == self.sequence + 1 and body["request_id"] not in self.ids, "REPLAY")
            binding = (body["pid"], body["owner"], body["graph"])
            require((not self.preflight and stage == "preflight" and self.sequence == 0) or
                    (self.preflight and stage == "gesture" and self.binding == binding), "PREFLIGHT_REQUIRED")
            # Consume before any RPC; refused/failed accepted requests cannot replay.
            self.sequence, self.binding = body["sequence"], binding
            self.ids.add(body["request_id"])
            with self.state_lock:
                self.active = (body["request_id"], body["pid"])
                self.cancelled.clear()
            self.controller.status()
            cursor = self.monitor.cursor()
            start, down, tracking, records = time.monotonic(), False, None, 0
            deadline = start + 5
            acks = 0
            failure = None
            try:
                for frame in body["frames"]:
                    wait = start + frame["at_ms"] / 1000 - time.monotonic()
                    if wait > 0:
                        self.cancelled.wait(wait)
                    require(not self.cancelled.is_set() and not self.stopping.is_set(), "CANCELLED")
                    require(time.monotonic() < deadline, "GESTURE_TIMEOUT")
                    self.last_point = frame
                    if frame["pressure"]:
                        # A timed-out RPC may still have scheduled a down.
                        self.finger_may_be_down = True
                    self.controller.touch(frame, timeout=min(1, deadline - time.monotonic()))
                    acks += 1
                    observed = self.monitor.next_frame(cursor, min(deadline, time.monotonic() + 1))
                    tracking, down = match_frame(observed, frame, self.classification, self.axes, tracking, down)
                    cursor, records = observed.sequence, records + observed.records
                    if not down:
                        self.finger_may_be_down = False
                require(not down and not self.finger_may_be_down, "MISSING_RELEASE")
                with self.monitor.condition:
                    require(self.monitor.parser.sequence == cursor and self.monitor.parser.fault is None and
                            self.monitor.parser.records == 0 and not self.monitor.parser.changed,
                            "UNOWNED_KERNEL_FRAMES")
                if stage == "preflight":
                    self.preflight = True
                return {"protocol": 1, "ok": True, "request_id": body["request_id"],
                        "sequence": body["sequence"], "stage": stage,
                        "transport": "SDK_EMULATOR_CONTROLLER_PROTOCOL_B", "classification": self.classification,
                        "kernel": {"down": True, "move": True, "up": True, "release": True,
                                   "frames": len(body["frames"]), "matched_frames": len(body["frames"]),
                                   "records": records, "tracking_id": tracking, "strokes": strokes},
                        "rpc": {"send_touch_acks": acks, "release_acknowledged": True},
                        "talkback_gesture_qualified": False}
            except BaseException as problem:
                failure = problem
                raise
            finally:
                try:
                    self.release()
                except Exception as cleanup:
                    self.stopping.set()
                    if failure is None:
                        raise
                    # Keep the original causal refusal; report cleanup separately.
                    failure.cleanup_error = str(cleanup) if isinstance(cleanup, Refusal) else "CLEANUP_FAILED"
        finally:
            with self.state_lock:
                self.active = None
            self.lock.release()

    def cancel(self, body):
        require(type(body) is dict and set(body) == {"nonce", "request_id", "pid"}, "BAD_FIELDS")
        require(type(body["nonce"]) is str and hmac.compare_digest(body["nonce"], self.nonce), "UNAUTHORIZED")
        with self.state_lock:
            require(self.active == (body["request_id"], body["pid"]), "NO_OWNED_OPERATION")
            self.cancelled.set()
        return {"protocol": 1, "ok": True, "request_id": body["request_id"], "cancel_requested": True,
                "talkback_gesture_qualified": False}

    def release(self):
        if self.finger_may_be_down and self.last_point:
            frame = {**self.last_point, "pressure": 0}
            # No accepted touch is replayed. The only failure follow-up is the
            # mandatory pressure-zero release for this one owned identifier.
            with self.monitor.condition:
                cursor = self.monitor.parser.sequence
            # One SDK release call <=1s, then fresh kernel release <=1s: <=2s
            # independent of client disconnect/cancel. The operation bound is 7s.
            self.controller.touch(frame)
            deadline = time.monotonic() + 1
            with self.monitor.condition:
                # A timed-out down may appear before its finally release. Never
                # mistake the initial/stale pressure-zero state for a receipt.
                while True:
                    released = [observed for observed in self.monitor.frames if
                                observed.sequence > cursor and observed.tracking == -1 and
                                observed.pressure == 0 and "tracking" in observed.changed]
                    if released:
                        require(self.monitor.parser.fault is None, "CLEANUP_KERNEL_CONTAMINATED")
                        break
                    require(not self.monitor.closed, "CLEANUP_MONITOR_CLOSED")
                    remaining = deadline - time.monotonic()
                    require(remaining > 0, "CLEANUP_RELEASE_NOT_PROVED")
                    self.monitor.condition.wait(remaining)
            self.finger_may_be_down = False


class Server(ThreadingHTTPServer):
    daemon_threads = False
    block_on_close = True
    allow_reuse_address = False

    def handle_error(self, request, client_address):
        # Default socketserver tracebacks can include client/capability contents.
        print("INPUTREADER_BRIDGE_REFUSED:HTTP_HANDLER_FAILED", file=sys.stderr)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def setup(self):
        # Also bound header/body stalls before do_POST is entered.
        self.request.settimeout(3)
        super().setup()

    def log_message(self, *_):
        pass  # Neither request bodies nor capabilities enter a log.

    def do_POST(self):
        request_id = None
        try:
            self.connection.settimeout(3)
            require(self.client_address[0] == "127.0.0.1", "NON_LOOPBACK")
            require(self.path in {"/v1/preflight", "/v1/gesture", "/v1/cancel"}, "NO_ENDPOINT")
            require(self.headers.get("Transfer-Encoding") is None and
                    len(self.headers.get_all("Content-Length", [])) == 1 and
                    self.headers.get("Content-Type") == "application/json", "BAD_HTTP")
            length = self.headers["Content-Length"]
            require(re.fullmatch(r"[0-9]{1,5}", length) and 0 < int(length) <= BODY_LIMIT, "BODY_LIMIT")
            raw = self.rfile.read(int(length))
            require(len(raw) == int(length), "TRUNCATED_BODY")
            body = unique_json(raw.decode("utf-8"))
            if type(body) is dict and type(body.get("request_id")) is str and re.fullmatch(
                    r"[A-Za-z0-9_-]{1,64}", body["request_id"]):
                request_id = body["request_id"]
            bridge = self.server.bridge
            result = bridge.cancel(body) if self.path == "/v1/cancel" else bridge.operate(
                body, self.path.rsplit("/", 1)[1])
            status = 200
        except Refusal as error:
            status = 409 if str(error) in {"BUSY", "REPLAY", "NO_OWNED_OPERATION"} else 422
            result = {"protocol": 1, "ok": False, "request_id": request_id, "error": str(error)}
            if getattr(error, "cleanup_error", None):
                result["cleanup_error"] = error.cleanup_error
        except Exception:
            status, result = 500, {"protocol": 1, "ok": False, "request_id": request_id,
                                   "error": "INTERNAL_REFUSAL"}
        data = json.dumps(result, separators=(",", ":")).encode()
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(data)
        except OSError:
            pass  # Operation already completed/released before response writing.
        self.close_connection = True


def public_policy(path):
    value = unique_json(path.read_text())
    require(type(value) is dict and set(value) == {"grpc.jwks", "grpc.jwk_active", "grpc.port",
                                                  "grpc.allowlist", "actual_loopback_binding"},
            "NOT_SANITIZED_PUBLIC_DISCOVERY")
    require(all(type(item) is str for item in value.values()), "BAD_PUBLIC_DISCOVERY")
    require(value["grpc.port"] == "8580" and value["actual_loopback_binding"].startswith("127.0.0.1:8580"),
            "CONTROLLER_NOT_LOOPBACK")
    directory = Path(value["grpc.jwks"])
    discovery = re.search(r"/avd/running/(\d+)/jwks/[A-Za-z0-9-]+/?$", str(directory))
    require(discovery is not None, "NOT_SDK_WATCHED_DIRECTORY")
    require(directory.is_dir() and not directory.is_symlink() and directory.stat().st_uid == os.getuid() and
            not directory.stat().st_mode & 0o022,
            "UNOWNED_WATCHED_DIRECTORY")
    active = Path(value["grpc.jwk_active"])
    require(active == directory / "active.jwk" and not active.is_symlink(), "NOT_PUBLIC_ACTIVE_KEYSET")
    require(Path(value["grpc.allowlist"]).name == "emulator_access.json", "NOT_SDK_POLICY")
    require(integer(int(discovery[1]), 1, 2**31 - 1), "BAD_EMULATOR_PID")
    return {"directory": directory, "active": active, "pid": int(discovery[1])}


def verify_listeners(text, pid, console_port):
    """Same live SDK PID must own the selected serial's console and controller."""
    lines = text.splitlines()
    require([line for line in lines if line.startswith("p")] == ["p" + str(pid)],
            "CONTROLLER_PID_MISMATCH")
    require([line for line in lines if line.startswith("u")] == ["u" + str(os.getuid())],
            "CONTROLLER_USER_MISMATCH")
    listeners = [line[1:] for line in lines if line.startswith("n")]
    ports = set()
    for listener in listeners:
        match = re.fullmatch(r"(?:127\.0\.0\.1|\[::1\]|\[::ffff:127\.0\.0\.1\]):(\d+)", listener)
        require(match is not None, "CONTROLLER_NOT_LOOPBACK")
        ports.add(int(match[1]))
    require(ports == {console_port, 8580}, "SERIAL_CONTROLLER_NOT_BOUND")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--public-runtime-policy", type=Path, required=True)
    parser.add_argument("--generated-proto-dir", type=Path, required=True)
    parser.add_argument("--ready-file", type=Path, required=True)
    parser.add_argument("--port", type=int, default=0, help="Loopback only; 0 selects an unused port")
    parser.add_argument("--lifetime-seconds", type=int, default=180)
    args = parser.parse_args()
    # Root selects the exact disposable owned serial; a physical device is refused.
    # This run's authorization is emulator-5580, not discovery of other emulators.
    selected = re.fullmatch(r"emulator-([0-9]{4,5})", args.serial)
    require(selected is not None, "PHYSICAL_DEVICE_REFUSED")
    console_port = int(selected[1])
    require(1024 <= console_port <= 65534 and console_port % 2 == 0 and console_port != 8580,
            "BAD_CONSOLE_PORT")
    require(args.adb.is_file() and args.adb.name == "adb" and os.access(args.adb, os.X_OK), "NO_ADB")
    require(0 <= args.port <= 65535 and 10 <= args.lifetime_seconds <= 300, "BAD_BOUND")
    require(all((args.generated_proto_dir / name).is_file() for name in
                ("emulator_controller_pb2.py", "emulator_controller_pb2_grpc.py")), "NO_SDK_PROTOS")
    policy = public_policy(args.public_runtime_policy)
    require(Path("/usr/sbin/lsof").is_file(), "NO_LISTENER_INSPECTOR")
    listeners = subprocess.run(["/usr/sbin/lsof", "-nP", "-a", "-p", str(policy["pid"]),
                                "-iTCP:" + str(console_port), "-iTCP:8580", "-sTCP:LISTEN", "-Fpun"],
                               capture_output=True, text=True, timeout=5)
    require(listeners.returncode == 0 and len(listeners.stdout) <= 8192, "LISTENER_INSPECTION_REFUSED")
    verify_listeners(listeners.stdout, policy["pid"], console_port)
    adb = [str(args.adb), "-s", args.serial]

    def read_adb(arguments):
        # Fixed startup-only commands; nothing supplied by the HTTP client.
        result = subprocess.run(adb + arguments, capture_output=True, text=True, timeout=10)
        require(result.returncode == 0 and len(result.stdout) <= 2_000_000, "ADB_PREFLIGHT_REFUSED")
        return result.stdout

    require(read_adb(["get-state"]).strip() == "device" and
            read_adb(["shell", "getprop", "ro.kernel.qemu"]).strip() == "1", "NOT_EMULATOR")
    classification, axes = parse_classification(read_adb(["shell", "dumpsys", "input"]))
    # Start before importing/opening grpc; no fork/subprocess occurs afterwards.
    process = subprocess.Popen(adb + ["shell", "getevent", "-lt", EVENT_PATH], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, bufsize=1)
    monitor = KernelMonitor(process)
    controller, server, bridge, ready_identity, ready_bytes = None, None, None, None, None
    ready_complete = False
    try:
        cursor = monitor.cursor()
        seed_absolute_position(monitor, read_adb(["shell", "dumpsys", "input"]),
                               classification, axes, cursor)
        controller = Controller(policy, args.generated_proto_dir)
        bridge = Bridge(controller, monitor, classification, axes, args.lifetime_seconds)
        server = Server(("127.0.0.1", args.port), Handler)
        server.bridge = bridge
        server.timeout = 0.2
        ready_bytes = json.dumps({"protocol": 1, "port": server.server_address[1],
                                  "nonce": bridge.nonce}, separators=(",", ":")).encode()
        fd = os.open(args.ready_file, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
        ready_identity = os.fstat(fd)
        with os.fdopen(fd, "wb") as output:
            output.write(ready_bytes)
            output.flush()
            os.fsync(output.fileno())
        ready_complete = True

        def stop(_signum, _frame):
            bridge.stopping.set()
            bridge.cancelled.set()

        signal.signal(signal.SIGTERM, stop)
        signal.signal(signal.SIGINT, stop)
        while not bridge.stopping.is_set() and time.monotonic() < bridge.expires:
            server.handle_request()
    finally:
        primary_failure = sys.exc_info()[1]
        if bridge:
            bridge.stopping.set()
            bridge.cancelled.set()
        # Wait for bounded active requests and their pressure-zero finally first.
        if server:
            server.server_close()
        cleanup_errors = []
        try:
            if bridge:
                bridge.release()
        except Exception:
            cleanup_errors.append("CLEANUP_RELEASE_FAILED")
        try:
            if controller:
                controller.close()
        except Exception:
            cleanup_errors.append("CONTROLLER_CLEANUP_FAILED")
        try:
            monitor.stop()
        except Exception:
            cleanup_errors.append("MONITOR_CLEANUP_FAILED")
        try:
            if ready_identity is not None:
                current = args.ready_file.lstat()
                require(stat.S_ISREG(current.st_mode) and
                        (current.st_dev, current.st_ino) == (ready_identity.st_dev, ready_identity.st_ino),
                        "READY_FILE_REPLACED")
                fd = os.open(args.ready_file, os.O_RDONLY | os.O_NOFOLLOW)
                with os.fdopen(fd, "rb") as source:
                    opened = os.fstat(source.fileno())
                    require((opened.st_dev, opened.st_ino) == (ready_identity.st_dev, ready_identity.st_ino),
                            "READY_FILE_REPLACED")
                    written = source.read(len(ready_bytes) + 1)
                    require(written == ready_bytes if ready_complete else ready_bytes.startswith(written),
                            "READY_FILE_REPLACED")
                current = args.ready_file.lstat()
                require((current.st_dev, current.st_ino) == (ready_identity.st_dev, ready_identity.st_ino),
                        "READY_FILE_REPLACED")
                args.ready_file.unlink()
        except Exception:
            cleanup_errors.append("READY_CLEANUP_FAILED")
        if cleanup_errors:
            code = "_AND_".join(cleanup_errors)
            if primary_failure is None:
                raise Refusal(code)
            primary_failure.cleanup_error = code


if __name__ == "__main__":
    try:
        main()
    except (KeyboardInterrupt, Exception) as error:
        # Never print SDK errors/tracebacks/JWTs/nonce or raw InputReader dumps.
        print("INPUTREADER_BRIDGE_REFUSED:" + (str(error) if isinstance(error, Refusal) else
                                               "HOST_SETUP_OR_CLEANUP_FAILED"), file=sys.stderr)
        if getattr(error, "cleanup_error", None):
            print("INPUTREADER_BRIDGE_CLEANUP_REFUSED:" + error.cleanup_error, file=sys.stderr)
        sys.exit(1)
