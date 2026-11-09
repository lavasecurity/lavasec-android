"""Controlled source-model tests; these are not device/InputReader qualification."""
from collections import deque
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import android_talkback_inputreader as driver


# Actual observed API36 format, shortened to the owned device's public fields.
CLASSIFICATION = """Event Hub State:
    2: virtio_input_multi_touch_1
      Classes: KEYBOARD | TOUCH | TOUCH_MT | SWITCH
      Path: /dev/input/event1
      Enabled: true
      AbsState: ABS_MT_SLOT=0, ABS_MT_POSITION_X=102, ABS_MT_POSITION_Y=16383, ABS_MT_TRACKING_ID=-1, ABS_MT_PRESSURE=0
Input Reader State (Nums of device: 13):
  Device 12: virtio_input_multi_touch_1
    EventHub Devices: [ 2 ]
    Sources: KEYBOARD | TOUCHSCREEN | STYLUS
    Touch Input Mapper (mode - DIRECT):
      DeviceType: TOUCH_SCREEN
      Raw Touch Axes:
        X: min=0, max=32767, flat=0, fuzz=0, resolution=0
        Y: min=0, max=32767, flat=0, fuzz=0, resolution=0
      Viewport INTERNAL: displayId=0, uniqueId=local:4619827259835644672, port=0, orientation=0, logicalFrame=[0, 0, 320, 640], physicalFrame=[0, 0, 320, 640], deviceSize=[320, 640], isActive=[1]
"""
NONCE = "a" * 64


def body(stage="preflight", sequence=1):
    return {"nonce": NONCE, "request_id": "request-" + str(sequence), "sequence": sequence,
            "stage": stage, "pid": 27110, "owner": 4521, "graph": 1, "window": 330, "source": 8123,
            "display": {"id": 0, "width": 320, "height": 640},
            "app_rect": {"left": 0, "top": 24, "right": 320, "bottom": 640},
            "frames": [{"at_ms": 0, "x": 1, "y": 332, "pressure": 1},
                       {"at_ms": 80, "x": 2, "y": 332, "pressure": 1},
                       {"at_ms": 160, "x": 2, "y": 332, "pressure": 0}]}


def emit(parser, records):
    frame = None
    for event, code, value in records:
        frame = parser.feed(f"[  101242.970695] {event} {code} {value:08x}\n") or frame
    return frame


class ControlledMonitor:
    def __init__(self):
        self.parser = driver.KernelParser()
        self.condition = threading.Condition()
        self.frames = deque(maxlen=256)
        self.closed = False
        self.process = SimpleNamespace(poll=lambda: None)

    def cursor(self):
        return driver.KernelMonitor.cursor(self)

    def next_frame(self, cursor, deadline):
        matches = [frame for frame in self.frames if frame.sequence > cursor]
        driver.require(len(matches) == 1, "UNOWNED_KERNEL_FRAMES")
        return matches[0]


class ControlledSdk:
    """Scheduling/records fixture only: never starts a real SDK channel."""
    def __init__(self, monitor, failure=None):
        self.monitor, self.failure = monitor, failure
        self.calls, self.next_tracking = [], 0

    def status(self):
        pass

    def touch(self, frame, timeout=1):
        self.calls.append(dict(frame))
        parser = self.monitor.parser
        if frame["pressure"] == 0 and self.failure == "release":
            raise driver.Refusal("RPC_REFUSED")
        records = []
        if frame["pressure"] == 1 and parser.tracking == -1:
            records.append(("EV_ABS", "ABS_MT_TRACKING_ID", self.next_tracking))
            self.next_tracking += 1
            records.append(("EV_ABS", "ABS_MT_PRESSURE", 1))
        for axis, size in (("X", 320), ("Y", 640)):
            value = int(frame[axis.lower()] * 32767 / size)
            if self.failure == "coordinate" and axis == "X":
                value += 10
            if value != getattr(parser, axis.lower()):
                records.append(("EV_ABS", "ABS_MT_POSITION_" + axis, value))
        if frame["pressure"] == 0:
            records += [("EV_ABS", "ABS_MT_PRESSURE", 0),
                        ("EV_ABS", "ABS_MT_TRACKING_ID", 0xFFFFFFFF)]
        records.append(("EV_SYN", "SYN_REPORT", 0))
        with self.monitor.condition:
            self.monitor.frames.append(emit(parser, records))
            self.monitor.condition.notify_all()
        if self.failure in {"down", "release"} and len(self.calls) == 1:
            raise driver.Refusal("RPC_REFUSED")


class InputReaderContractTest(unittest.TestCase):
    def setUp(self):
        self.classification, self.axes = driver.parse_classification(CLASSIFICATION)

    def refusal(self, code, operation):
        with self.assertRaisesRegex(driver.Refusal, "^" + code + "$"):
            operation()

    def test_current_human_readable_classification_joins_exact_eventhub_and_reader(self):
        self.assertEqual((2, 12, 320, 640), (self.classification["eventhub_id"],
                         self.classification["inputreader_id"], self.classification["width"],
                         self.classification["height"]))
        for old, new in (("mode - DIRECT", "mode - DISABLED"), ("TOUCHSCREEN", "MOUSE"),
                         ("isActive=[1]", "isActive=[0]"), ("physicalFrame=[0, 0, 320, 640]",
                          "physicalFrame=[0, 0, 720, 1280]"), ("ABS_MT_TRACKING_ID=-1", "ABS_MT_TRACKING_ID=0")):
            with self.subTest(new=new), self.assertRaises(driver.Refusal):
                driver.parse_classification(CLASSIFICATION.replace(old, new))

    def test_fresh_released_per_slot_state_admits_only_complete_unambiguous_actual_axes(self):
        self.assertEqual((102, 16383), driver.released_absolute_position(CLASSIFICATION))
        for old, new in (("ABS_MT_SLOT=0", "ABS_MT_SLOT=1"),
                         ("ABS_MT_TRACKING_ID=-1", "ABS_MT_TRACKING_ID=0"),
                         ("ABS_MT_PRESSURE=0", "ABS_MT_PRESSURE=1"),
                         ("ABS_MT_POSITION_Y=16383", "ABS_MT_POSITION_Y=32768"),
                         ("ABS_MT_POSITION_Y=16383, ", ""),
                         ("ABS_MT_POSITION_Y=16383", "ABS_MT_POSITION_Y=16383, ABS_MT_POSITION_Y=16383")):
            with self.subTest(new=new), self.assertRaises(driver.Refusal):
                driver.released_absolute_position(CLASSIFICATION.replace(old, new))

    def test_monitor_snapshot_seeds_only_axes_and_refuses_intervening_partial_or_complete_records(self):
        monitor = ControlledMonitor()
        driver.seed_absolute_position(monitor, CLASSIFICATION, self.classification, self.axes, 0)
        self.assertEqual((102, 16383, -1, 0, 0, set()),
                         (monitor.parser.x, monitor.parser.y, monitor.parser.tracking,
                          monitor.parser.pressure, monitor.parser.sequence, monitor.parser.changed))
        self.refusal("ABSOLUTE_STATE_ALREADY_OBSERVED", lambda: driver.seed_absolute_position(
            monitor, CLASSIFICATION, self.classification, self.axes, 0))
        for records in ([ ("EV_ABS", "ABS_MT_POSITION_X", 204) ],
                        [ ("EV_ABS", "ABS_MT_POSITION_X", 204), ("EV_SYN", "SYN_REPORT", 0) ]):
            raced = ControlledMonitor()
            emit(raced.parser, records)
            self.refusal("ABSOLUTE_STATE_RACED", lambda: driver.seed_absolute_position(
                raced, CLASSIFICATION, self.classification, self.axes, 0))
        changed = CLASSIFICATION.replace("Device 12:", "Device 11:")
        self.refusal("CLASSIFICATION_CHANGED", lambda: driver.seed_absolute_position(
            ControlledMonitor(), changed, self.classification, self.axes, 0))

    def test_allowed_partial_metadata_records_refuse_cursor_and_axis_seeding(self):
        for code in ("ABS_MT_SLOT", "ABS_MT_TOOL_TYPE"):
            with self.subTest(code=code):
                monitor = ControlledMonitor()
                cursor = monitor.cursor()
                self.assertIsNone(emit(monitor.parser, [("EV_ABS", code, 0)]))
                # Both records are valid metadata and leave changed/fault empty;
                # without SYN_REPORT they are still an intervening partial frame.
                self.assertEqual((0, 1, set(), None), (monitor.parser.sequence,
                                 monitor.parser.records, monitor.parser.changed, monitor.parser.fault))
                self.refusal("EXISTING_KERNEL_CONTACT", monitor.cursor)
                self.refusal("ABSOLUTE_STATE_RACED", lambda: driver.seed_absolute_position(
                    monitor, CLASSIFICATION, self.classification, self.axes, cursor))
                self.assertEqual((None, None), (monitor.parser.x, monitor.parser.y))

    def test_persistent_unchanged_axes_need_no_new_axis_records_but_all_three_new_frames_are_required(self):
        monitor = ControlledMonitor()
        driver.seed_absolute_position(monitor, CLASSIFICATION, self.classification, self.axes, 0)
        # After ef's release both axes can already equal the next preflight's
        # initial point. Linux emits only the new tracking/pressure on that DOWN.
        down = emit(monitor.parser, [("EV_ABS", "ABS_MT_TRACKING_ID", 0),
                                    ("EV_ABS", "ABS_MT_PRESSURE", 1), ("EV_SYN", "SYN_REPORT", 0)])
        expected = {"at_ms": 0, "x": 1, "y": 320, "pressure": 1}
        tracking, active = driver.match_frame(down, expected, self.classification, self.axes, None, False)
        self.assertEqual((0, True), (tracking, active))
        self.assertEqual(frozenset({"tracking", "pressure"}), down.changed)
        moved = emit(monitor.parser, [("EV_ABS", "ABS_MT_POSITION_X", 204), ("EV_SYN", "SYN_REPORT", 0)])
        tracking, active = driver.match_frame(moved, {**expected, "x": 2}, self.classification,
                                             self.axes, tracking, active)
        released = emit(monitor.parser, [("EV_ABS", "ABS_MT_PRESSURE", 0),
                                        ("EV_ABS", "ABS_MT_TRACKING_ID", 0xFFFFFFFF),
                                        ("EV_SYN", "SYN_REPORT", 0)])
        tracking, active = driver.match_frame(released, {**expected, "x": 2, "pressure": 0},
                                             self.classification, self.axes, tracking, active)
        self.assertEqual((0, False, 3, 8), (tracking, active, monitor.parser.sequence,
                                          down.records + moved.records + released.records))
        unknown = driver.KernelFrame(1, 0, 102, None, 1, frozenset({"tracking", "pressure", "x"}), 4)
        self.refusal("KERNEL_COORDINATE_MISMATCH", lambda: driver.match_frame(
            unknown, expected, self.classification, self.axes, None, False))
        mismatched = driver.KernelFrame(1, 0, 102, 16380, 1, down.changed, down.records)
        self.refusal("KERNEL_COORDINATE_MISMATCH", lambda: driver.match_frame(
            mismatched, expected, self.classification, self.axes, None, False))
        stale_pressure = driver.KernelFrame(1, 0, 102, 16383, 1, frozenset({"tracking"}), 2)
        self.refusal("MISSING_KERNEL_DOWN", lambda: driver.match_frame(
            stale_pressure, expected, self.classification, self.axes, None, False))

    def test_listener_coupling_refuses_another_pid_public_binding_or_missing_console(self):
        valid = "p59491\nu" + str(os.getuid()) + "\nn127.0.0.1:5580\nn[::ffff:127.0.0.1]:8580\n"
        driver.verify_listeners(valid, 59491, 5580)
        for invalid in (valid.replace("59491", "59492"), valid.replace("127.0.0.1:5580", "*:5580"),
                        "p59491\nu" + str(os.getuid()) + "\nn127.0.0.1:8580\n",
                        valid.replace("u" + str(os.getuid()), "u" + str(os.getuid() + 1))):
            with self.assertRaises(driver.Refusal):
                driver.verify_listeners(invalid, 59491, 5580)

    def test_app_rect_is_distinct_from_full_display_and_all_frames_stay_inside_it(self):
        self.assertEqual(1, driver.validate_request(body(), NONCE, self.classification, "preflight"))
        for key, value, code in (("nonce", "b" * 64, "UNAUTHORIZED"),
                                 ("display", {"id": 1, "width": 320, "height": 640}, "VIEWPORT_MISMATCH")):
            request = body()
            request[key] = value
            self.refusal(code, lambda: driver.validate_request(request, NONCE, self.classification, "preflight"))
        request = body()
        request["frames"][0]["y"] = 23
        self.refusal("OUT_OF_BOUNDS", lambda: driver.validate_request(request, NONCE, self.classification, "preflight"))

    def test_missing_release_stationary_move_unsafe_preflight_and_arbitrary_fields_refuse(self):
        cases = []
        missing = body(); missing["frames"][-1]["pressure"] = 1; missing["frames"][-1]["x"] = 3
        cases.append(missing)
        stationary = body(); stationary["frames"][1]["x"] = 1
        cases.append(stationary)
        unsafe = body(); unsafe["frames"][0]["x"] = 5
        cases.append(unsafe)
        extra = body(); extra["rpc"] = "sendKey"
        cases.append(extra)
        for request in cases:
            with self.assertRaises(driver.Refusal):
                driver.validate_request(request, NONCE, self.classification, "preflight")
        self.refusal("DUPLICATE_FIELD", lambda: driver.unique_json('{"nonce":"a","nonce":"b"}'))

    def test_double_tap_two_complete_contacts_is_admitted_without_a_scroll_distance(self):
        request = body("gesture", 4)
        request["frames"] = [{"at_ms": at, "x": x, "y": 332, "pressure": pressure}
                             for at, x, pressure in ((0, 160, 1), (40, 161, 1), (70, 161, 0),
                                                     (150, 160, 1), (190, 161, 1), (220, 161, 0))]
        self.assertEqual(2, driver.validate_request(request, NONCE, self.classification, "gesture"))

    def test_kernel_parser_diagnostics_do_not_count_as_records_or_receipts(self):
        parser = driver.KernelParser()
        for _ in range(28):
            self.assertIsNone(parser.feed("I1004 ev_poll_posix.cc FD from fork parent still in poll list\n"))
        down = emit(parser, [("EV_ABS", "ABS_MT_TRACKING_ID", 0), ("EV_ABS", "ABS_MT_PRESSURE", 1),
                             ("EV_ABS", "ABS_MT_POSITION_X", 102), ("EV_ABS", "ABS_MT_POSITION_Y", 23039),
                             ("EV_SYN", "SYN_REPORT", 0)])
        moved = emit(parser, [("EV_ABS", "ABS_MT_POSITION_X", 204), ("EV_SYN", "SYN_REPORT", 0)])
        up = emit(parser, [("EV_ABS", "ABS_MT_PRESSURE", 0), ("EV_ABS", "ABS_MT_TRACKING_ID", 0xFFFFFFFF),
                           ("EV_SYN", "SYN_REPORT", 0)])
        self.assertEqual((3, 10), (parser.sequence, down.records + moved.records + up.records))
        self.assertEqual((-1, 0), (up.tracking, up.pressure))
        self.assertIsNone(parser.fault)
        emit(parser, [("EV_ABS", "ABS_MT_SLOT", 1)])
        self.assertEqual("OTHER_KERNEL_CONTACT", parser.fault)

    def bridge(self, failure=None):
        monitor = ControlledMonitor()
        sdk = ControlledSdk(monitor, failure)
        bridge = driver.Bridge(sdk, monitor, self.classification, self.axes, 180)
        bridge.nonce = NONCE
        return bridge, sdk, monitor

    def test_matching_scheduled_frames_are_distinct_from_talkback_qualification_and_no_replay(self):
        bridge, sdk, monitor = self.bridge()
        result = bridge.operate(body(), "preflight")
        self.assertFalse(result["talkback_gesture_qualified"])
        self.assertEqual((3, 3, 1), (result["kernel"]["frames"], result["kernel"]["matched_frames"],
                                     result["kernel"]["strokes"]))
        self.assertEqual(3, len(sdk.calls))
        self.assertEqual(-1, monitor.parser.tracking)
        self.refusal("REPLAY", lambda: bridge.operate(body(), "preflight"))
        self.assertEqual(3, len(sdk.calls))
        switched = body("gesture", 2); switched["owner"] += 1
        self.refusal("PREFLIGHT_REQUIRED", lambda: bridge.operate(switched, "gesture"))
        self.assertEqual(3, len(sdk.calls))

    def test_scheduling_failure_still_releases_fresh_kernel_contact_without_retrying_down(self):
        bridge, sdk, monitor = self.bridge("down")
        self.refusal("RPC_REFUSED", lambda: bridge.operate(body(), "preflight"))
        self.assertEqual([1, 0], [frame["pressure"] for frame in sdk.calls])
        self.assertEqual((-1, 2), (monitor.parser.tracking, monitor.parser.sequence))
        self.assertFalse(bridge.preflight)
        self.assertIsNone(bridge.active)

    def test_allowed_partial_metadata_after_release_never_certifies_preflight(self):
        for code in ("ABS_MT_SLOT", "ABS_MT_TOOL_TYPE"):
            with self.subTest(code=code):
                bridge, sdk, monitor = self.bridge()
                original_touch = sdk.touch

                def touch_with_partial_record(frame, timeout=1):
                    original_touch(frame, timeout)
                    if frame["pressure"] == 0:
                        with monitor.condition:
                            emit(monitor.parser, [("EV_ABS", code, 0)])

                with patch.object(sdk, "touch", side_effect=touch_with_partial_record):
                    self.refusal("UNOWNED_KERNEL_FRAMES", lambda: bridge.operate(body(), "preflight"))
                self.assertEqual([1, 1, 0], [frame["pressure"] for frame in sdk.calls])
                self.assertEqual((3, 1, set(), None), (monitor.parser.sequence,
                                 monitor.parser.records, monitor.parser.changed, monitor.parser.fault))
                self.assertFalse(bridge.preflight)
                self.assertIsNone(bridge.active)
                self.refusal("REPLAY", lambda: bridge.operate(body(), "preflight"))
                self.assertEqual(3, len(sdk.calls))

    def test_kernel_coordinate_mismatch_refuses_and_still_releases(self):
        bridge, sdk, monitor = self.bridge("coordinate")
        self.refusal("KERNEL_COORDINATE_MISMATCH", lambda: bridge.operate(body(), "preflight"))
        self.assertEqual([1, 0], [frame["pressure"] for frame in sdk.calls])
        self.assertEqual(-1, monitor.parser.tracking)

    def test_release_failure_preserves_causal_refusal_and_stops_transport(self):
        bridge, sdk, monitor = self.bridge("release")
        with self.assertRaises(driver.Refusal) as failure:
            bridge.operate(body(), "preflight")
        self.assertEqual("RPC_REFUSED", str(failure.exception))
        self.assertEqual("RPC_REFUSED", failure.exception.cleanup_error)
        self.assertTrue(bridge.stopping.is_set())
        self.assertEqual([1, 0], [frame["pressure"] for frame in sdk.calls])

    def test_concurrent_operation_and_foreign_cancel_never_dispatch(self):
        bridge, sdk, _ = self.bridge()
        bridge.lock.acquire()
        try:
            self.refusal("BUSY", lambda: bridge.operate(body(), "preflight"))
        finally:
            bridge.lock.release()
        self.refusal("NO_OWNED_OPERATION", lambda: bridge.cancel(
            {"nonce": NONCE, "request_id": "foreign", "pid": 27110}))
        self.assertEqual([], sdk.calls)

    def test_jwt_scopes_lifetime_and_header_keep_private_material_in_memory(self):
        captured = []
        controller = driver.Controller.__new__(driver.Controller)
        controller.key, controller.kid, controller.token, controller.last_refresh = object(), "own", None, 0
        class Jwt:
            @staticmethod
            def encode(claims, key, **kwargs):
                captured.append((claims, key, kwargs))
                return "own-memory-only-fixture"
        controller.jwt = Jwt
        with patch.object(driver.time, "time", return_value=1000):
            controller.metadata()
        claims, key, options = captured[0]
        self.assertIs(controller.key, key)
        self.assertEqual(("gradle-utp-emulator-control", 60, driver.AUDIENCES),
                         (claims["iss"], claims["exp"] - claims["iat"], claims["aud"]))
        self.assertEqual({"kid": "own", "typ": None}, options["headers"])
        self.assertEqual("RS256", options["algorithm"])

    def test_own_public_registration_is_private_and_replaced_file_is_never_deleted(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            public = {"kid": "owned", "n": "public-only", "e": "AQAB"}
            registration = driver.PublicRegistration(root, public)
            self.assertEqual(0o600, registration.path.stat().st_mode & 0o777)
            self.assertEqual({"keys": [public]}, json.loads(registration.path.read_text()))
            replacement = root / "replacement"
            replacement.write_text("foreign")
            os.replace(replacement, registration.path)
            self.refusal("REGISTRATION_REPLACED", registration.remove)
            self.assertEqual("foreign", registration.path.read_text())

    def test_complete_own_registration_is_removed_and_preexisting_registration_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            public = {"kid": "owned", "n": "public-only", "e": "AQAB"}
            registration = driver.PublicRegistration(root, public)
            registration.remove()
            self.assertFalse(registration.path.exists())
            registration.path.write_text("preexisting")
            with self.assertRaises(FileExistsError):
                driver.PublicRegistration(root, public)
            self.assertEqual("preexisting", registration.path.read_text())

    def test_cancel_only_signals_exact_current_operation_and_never_sends_input_itself(self):
        bridge, sdk, _ = self.bridge()
        bridge.active = ("request-1", 27110)
        self.refusal("NO_OWNED_OPERATION", lambda: bridge.cancel(
            {"nonce": NONCE, "request_id": "request-1", "pid": 27111}))
        result = bridge.cancel({"nonce": NONCE, "request_id": "request-1", "pid": 27110})
        self.assertTrue(result["cancel_requested"])
        self.assertTrue(bridge.cancelled.is_set())
        self.assertEqual([], sdk.calls)

    def test_own_partial_public_write_is_removed_without_touching_preexisting_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            foreign = root / "existing.jwk"; foreign.write_text("foreign")
            original = os.fdopen
            class PartialWriter:
                def __init__(self, fd): self.output = original(fd, "wb")
                def __enter__(self): return self
                def __exit__(self, *_): self.output.close()
                def write(self, data): self.output.write(data[:7])
                def flush(self): self.output.flush(); raise OSError("controlled partial flush failure")
            def fdopen(fd, mode):
                return PartialWriter(fd) if mode == "wb" else original(fd, mode)
            with patch.object(driver.os, "fdopen", side_effect=fdopen), self.assertRaises(OSError):
                driver.PublicRegistration(root, {"kid": "owned", "n": "public-only"})
            self.assertFalse((root / "owned.jwk").exists())
            self.assertEqual("foreign", foreign.read_text())


if __name__ == "__main__":
    unittest.main()
