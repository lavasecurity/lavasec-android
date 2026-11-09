import io
import json
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import android_native_mount_trace as trace

ROOT = Path(__file__).resolve().parents[1]


def soft(child=67148, attempted=67746, previous=67000, parent="ReactViewGroup", view="ReactTextView", tag="unknown:SurfaceMountingManager"):
    return (f"10-09 02:10:48.408  5885  5885 E {tag}: java.lang.IllegalStateException: "
            f"addViewAt: cannot insert view [{child}] into parent [{attempted}]: View already has a parent: "
            f"[{previous}] Parent: {parent} View: {view}\n").encode()


def fatal(child=67148, attempted=67746, index=9):
    return (f"10-09 02:10:48.408  5885  5885 E AndroidRuntime: java.lang.IllegalStateException: "
            f"addViewAt: failed to insert view [{child}] into parent [{attempted}] at index {index}\n").encode()


def witness_value():
    return {"schema": 1, "child": 67148, "attemptedParent": 67746, "previousParent": 67000,
            "mainThread": True, "currentContext": True, "sameWindow": True, "resolved": True,
            "status": "resolved", "childTruncated": False, "attemptedParentTruncated": False,
            "childChain": [{"tag": 67148, "class": "ReactTextView", "marker": "other", "parentIndex": -1},
                           {"tag": 67000, "class": "ReactViewGroup", "marker": "plus.context", "parentIndex": 3}],
            "attemptedParentChain": [{"tag": 67746, "class": "ReactViewGroup", "marker": "screen.scroll", "parentIndex": 0}]}


def witness(value=None, encoded=None, tag="LavaFabricMountWitness"):
    payload = encoded if encoded is not None else json.dumps(witness_value() if value is None else value, separators=(",", ":"))
    return (f"10-09 02:10:48.408  5885  5885 E {tag}: ParentWitness {payload}\n").encode()


class NativeMountTraceTests(unittest.TestCase):
    def test_exact_installed_soft_and_fatal_messages_keep_only_logged_independent_facts(self):
        value = trace.retained_tail(io.BytesIO(soft() + fatal()))
        self.assertEqual(1, value["schema"])
        self.assertEqual([
            {"kind": "already-parent", "pid": 5885, "tid": 5885, "millisOfDay": 7848408,
             "child": 67148, "attemptedParent": 67746, "previousParent": 67000, "index": None,
             "previousParentClass": "ReactViewGroup", "childClass": "ReactTextView"},
            {"kind": "insert-failed", "pid": 5885, "tid": 5885, "millisOfDay": 7848408,
             "child": 67148, "attemptedParent": 67746, "previousParent": None, "index": 9,
             "previousParentClass": None, "childClass": None},
        ], value["events"])
        self.assertIsNotNone(trace.project_line(soft(tag="SurfaceMountingManager", previous=-1)))

    def test_unknown_classes_become_fixed_other_and_never_escape_into_json(self):
        private = "PrivateCredentialView_Secret123"
        value = trace.retained_tail(io.BytesIO(soft(parent=private, view=private)))
        self.assertEqual("other", value["events"][0]["previousParentClass"])
        self.assertEqual("other", value["events"][0]["childClass"])
        self.assertNotIn(private, json.dumps(value))
        for name in sorted(trace.CLASSES):
            self.assertEqual(name, trace.project_line(soft(parent=name, view=name))["childClass"])

    def test_integer_and_time_ranges_refuse_invalid_actual_log_fields(self):
        invalid = [soft(child=0), soft(child=2**31), soft(attempted=0), soft(previous=-2), soft(previous=2**31),
                   fatal(index=-1), fatal(index=2**31), soft().replace(b"  5885  5885", b"  0  5885"),
                   soft().replace(b"  5885  5885", b"  5885  2147483648"),
                   soft().replace(b"02:10:48.408", b"24:10:48.408"),
                   soft().replace(b"02:10:48.408", b"02:60:48.408"),
                   soft().replace(b"02:10:48.408", b"02:10:60.408"),
                   soft().replace(b"10-09", b"02-30"), soft().replace(b"[67148]", b"[1.5]"),
                   soft().replace(b"[67148]", "[６７１４８]".encode())]
        self.assertEqual([], trace.retained_tail(io.BytesIO(b"".join(invalid)))["events"])
        self.assertIsNotNone(trace.project_line(soft(child=2**31-1, previous=0)))
        self.assertIsNotNone(trace.project_line(fatal(index=0)))

    def test_tags_trailing_private_hierarchy_and_unrelated_exception_text_are_never_retained(self):
        bad = [soft(tag="ReactNative"), soft(tag="AndroidRuntime"), fatal().replace(b"AndroidRuntime", b"SurfaceMountingManager"),
               soft().replace(b" E ", b" W "), soft().replace(b"\n", b" private-domain.example\n"),
               b"10-09 02:10:48.408 5885 5885 E SurfaceMountingManager: <ViewGroup tag=67148 class=PrivateLabel>\n",
               b"10-09 02:10:48.408 5885 5885 E AndroidRuntime: FATAL EXCEPTION: private-value\n",
               b"private-value\n", soft().replace(b"ReactTextView", b"Private.View"),
               soft().replace(b"ReactTextView", b"Private View")]
        value = trace.retained_tail(io.BytesIO(b"".join(bad) + soft()))
        self.assertEqual([trace.project_line(soft())], value["events"])
        self.assertNotIn("private", json.dumps(value).lower())

    def test_utf8_oversized_and_unterminated_lines_cannot_displace_valid_tail(self):
        bad = soft().replace(b"ReactTextView", b"\xff") + b"x" * 5000 + b"\n" + soft().rstrip(b"\n")
        self.assertEqual([trace.project_line(soft())], trace.retained_tail(io.BytesIO(soft() + bad))["events"])
        self.assertEqual([], trace.retained_tail(io.BytesIO(soft().rstrip(b"\n")))["events"])

    def test_latest_eight_events_are_stable_in_physical_input_order(self):
        stream = b"".join(soft(child=index+1) if index % 2 == 0 else fatal(child=index+1) for index in range(20))
        first = trace.retained_tail(io.BytesIO(stream))
        self.assertEqual(list(range(13, 21)), [row["child"] for row in first["events"]])
        self.assertEqual(8, len(first["events"]))
        self.assertEqual(first, trace.retained_tail(io.BytesIO(stream)))

    def test_total_input_budget_is_a_refusal_not_a_partial_receipt(self):
        with self.assertRaises(trace.InputRefused):
            trace.retained_tail(io.BytesIO(soft() + b"x" * trace.MAX_INPUT_BYTES))

    def test_actual_cli_creates_private_immutable_json_and_refuses_without_echoing_input(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "receipt.json"
            command = [sys.executable, str(ROOT / "ci/android_native_mount_trace.py"), "--output", str(output)]
            run = lambda payload: subprocess.run(command, input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            result = run(soft() + fatal())
            self.assertEqual(0, result.returncode)
            self.assertEqual(b"", result.stdout)
            self.assertEqual(b"", result.stderr)
            before = output.read_bytes()
            self.assertEqual(trace.retained_tail(io.BytesIO(soft() + fatal())), json.loads(before))
            self.assertEqual(0o600, stat.S_IMODE(output.stat().st_mode))
            refused = run(b"private-credential-value\n")
            self.assertEqual(2, refused.returncode)
            self.assertEqual(before, output.read_bytes())
            self.assertEqual(b"Native mount diagnostic refused.\n", refused.stderr)
            self.assertNotIn(b"private", refused.stdout + refused.stderr)
            missing = Path(directory) / "overflow.json"
            overflow = subprocess.run(command[:-1] + [str(missing)], input=b"x" * (trace.MAX_INPUT_BYTES+1),
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            self.assertEqual(2, overflow.returncode)
            self.assertFalse(missing.exists())

    def test_parent_witness_retains_actual_independent_chains_and_negative_removal_index(self):
        value = trace.project_line(witness())
        self.assertEqual("parent-witness", value["kind"])
        self.assertEqual((5885, 5885, 7848408), (value["pid"], value["tid"], value["millisOfDay"]))
        self.assertEqual(-1, value["childChain"][0]["parentIndex"])
        self.assertEqual("plus.context", value["childChain"][1]["marker"])
        self.assertEqual("screen.scroll", value["attemptedParentChain"][0]["marker"])
        self.assertNotIn("index", value)  # A witness never borrows the fatal insertion index.
        combined = trace.retained_tail(io.BytesIO(witness() + fatal(index=9)))
        self.assertEqual(["parent-witness", "insert-failed"], [row["kind"] for row in combined["events"]])
        self.assertEqual(-1, combined["events"][0]["childChain"][0]["parentIndex"])
        self.assertEqual(9, combined["events"][1]["index"])

    def test_unresolved_witnesses_are_explicit_and_cannot_invent_a_chain_or_window(self):
        for status, main, context, window in (
            ("off-main", False, None, None), ("no-context", True, False, None),
            ("context-changed", True, False, None), ("wrong-host", True, True, None),
            ("inactive-context", True, True, None), ("missing-view", True, True, None),
            ("wrong-window", True, True, False), ("non-view-parent", True, True, True),
            ("identity-mismatch", True, True, None), ("observer-error", True, None, None),
        ):
            with self.subTest(status=status):
                payload = witness_value()
                payload.update(status=status, mainThread=main, currentContext=context, sameWindow=window,
                               resolved=False, childChain=[], attemptedParentChain=[])
                projected = trace.project_line(witness(payload))
                self.assertIsNotNone(projected)
                self.assertEqual(status, projected["status"])
                self.assertIs(projected["sameWindow"], window)
                self.assertEqual([], projected["childChain"])
                payload["childChain"] = witness_value()["childChain"]
                self.assertIsNone(trace.project_line(witness(payload)))

    def test_witness_private_fields_are_refused_and_unknown_class_or_marker_becomes_other(self):
        payload = witness_value()
        payload["childChain"][0].update({"class": "PrivateSecretView", "marker": "filter.rule.blocked.private.example"})
        value = trace.project_line(witness(payload))
        self.assertEqual("other", value["childChain"][0]["class"])
        self.assertEqual("other", value["childChain"][0]["marker"])
        self.assertNotIn("Private", json.dumps(value))
        self.assertNotIn("private.example", json.dumps(value))
        for location in ("top", "node"):
            payload = witness_value()
            target = payload if location == "top" else payload["childChain"][0]
            target["text"] = "private-secret.example"
            self.assertIsNone(trace.project_line(witness(payload)))
        encoded = json.dumps(witness_value(), separators=(",", ":"))
        self.assertIsNone(trace.project_line(witness(encoded=encoded.replace('"schema":1', '"schema":1,"schema":1'))))
        self.assertIsNone(trace.project_line(witness(encoded=encoded.replace('"tag":67148', '"tag":67148,"tag":67148'))))
        self.assertIsNone(trace.project_line(witness(encoded=encoded + " trailing-private")))
        self.assertIsNone(trace.project_line(witness(tag="AndroidRuntime")))
        self.assertIsNone(trace.project_line(witness().replace(b"ParentWitness ", b"Hierarchy ")))

    def test_witness_invalid_ranges_flags_and_partial_malformed_chains_are_rejected(self):
        bad = []
        for key, item in (("schema", True), ("schema", 2), ("child", True), ("child", 0),
                          ("attemptedParent", 2**31), ("previousParent", -2), ("resolved", 1),
                          ("mainThread", 1), ("currentContext", 1), ("sameWindow", "true"),
                          ("childTruncated", 1), ("status", "private-status")):
            payload = witness_value()
            payload[key] = item
            bad.append(witness(payload))
        for key, item in (("tag", True), ("tag", -2), ("parentIndex", -2),
                          ("parentIndex", 2**31), ("parentIndex", 1.25),
                          ("class", "private.View"), ("class", []), ("marker", [])):
            payload = witness_value()
            payload["childChain"][0][key] = item
            bad.append(witness(payload))
        payload = witness_value()
        payload["childChain"] *= 5
        bad.append(witness(payload))
        for key, item in (("childChain", []), ("attemptedParentChain", []), ("childTruncated", True),
                          ("mainThread", False), ("sameWindow", None), ("currentContext", False)):
            payload = witness_value()
            payload[key] = item
            bad.append(witness(payload))
        payload = witness_value()
        payload["childChain"][1]["tag"] += 1
        bad.append(witness(payload))
        payload = witness_value()
        payload["attemptedParentChain"][0]["tag"] += 1
        bad.append(witness(payload))
        self.assertEqual([], trace.retained_tail(io.BytesIO(b"".join(bad)))["events"])

    def test_fixed_maximal_legitimate_witness_fits_derived_line_and_payload_budgets(self):
        longest_class = max(trace.CLASSES, key=len)
        longest_marker = max(trace.MARKERS, key=len)
        row = {"tag": trace.INT_MAX, "parentIndex": trace.INT_MAX,
               "class": longest_class, "marker": longest_marker}
        payload = witness_value()
        payload.update(child=trace.INT_MAX, attemptedParent=trace.INT_MAX, previousParent=trace.INT_MAX,
                       childTruncated=True, attemptedParentTruncated=True,
                       childChain=[dict(row) for _ in range(8)], attemptedParentChain=[dict(row) for _ in range(8)])
        encoded = json.dumps(payload, separators=(",", ":"))
        self.assertLess(len(encoded.encode()), 3072)
        raw = witness(payload).replace(b"  5885  5885", b" 2147483647 2147483647")
        self.assertLess(len(raw), trace.MAX_LINE_BYTES)
        self.assertIsNotNone(trace.project_line(raw))
        payload["childChain"].append(dict(row))
        self.assertIsNone(trace.project_line(witness(payload)))

    def test_witness_utf8_oversize_and_unterminated_input_does_not_displace_valid_tail(self):
        encoded = json.dumps(witness_value(), separators=(",", ":"))
        bad = witness(encoded=encoded + "x" * 3073)
        bad += witness().replace(b"ReactTextView", b"\xff")
        bad += witness(encoded=encoded.replace('"marker":"other"', '"marker":"\u79d8\u5bc6"'))
        bad += witness(encoded=encoded[:-1])
        bad += b"x" * (trace.MAX_LINE_BYTES + 1) + b"\n"
        bad += witness().rstrip(b"\n")
        self.assertEqual([trace.project_line(witness())], trace.retained_tail(io.BytesIO(witness() + bad))["events"])

    def test_mixed_witness_tail_remains_last_eight_in_actual_physical_order(self):
        chunks = []
        for number in range(20):
            payload = witness_value()
            payload["child"] = number + 1
            payload["childChain"][0]["tag"] = number + 1
            chunks.append(witness(payload) if number % 2 else soft(child=number+1))
        first = trace.retained_tail(io.BytesIO(b"".join(chunks)))
        self.assertEqual(list(range(13, 21)), [row["child"] for row in first["events"]])
        self.assertEqual(first, trace.retained_tail(io.BytesIO(b"".join(chunks))))

    def test_actual_cli_projects_witness_privately_without_raw_marker_or_hierarchy(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "parent.json"
            payload = witness_value()
            payload["childChain"][0]["marker"] = "filter.rule.allowed.private.example"
            command = [sys.executable, str(ROOT / "ci/android_native_mount_trace.py"), "--output", str(output)]
            result = subprocess.run(command, input=witness(payload), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            self.assertEqual(0, result.returncode)
            self.assertEqual(b"", result.stdout + result.stderr)
            actual = output.read_bytes()
            self.assertNotIn(b"private.example", actual)
            self.assertEqual(0o600, stat.S_IMODE(output.stat().st_mode))
            self.assertEqual("parent-witness", json.loads(actual)["events"][0]["kind"])
            self.assertEqual("other", json.loads(actual)["events"][0]["childChain"][0]["marker"])
