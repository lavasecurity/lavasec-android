import io
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import android_navigation_trace as trace


def row(boundary="guard-parent", **changes):
    value = {key: 1 for key in trace.NUMERIC}
    value.update({key: False for key in trace.BOOLEAN})
    return {**value, "boundary": boundary, **changes}


def wire(value):
    return trace.PREFIX + json.dumps(value).encode() + b"\n"


class NavigationTraceTests(unittest.TestCase):
    def test_latest_256_per_boundary_survive_earlier_host_scopes_in_input_order(self):
        stream = b"".join(wire(row(boundary, commits=i, events=i % 256 + 1,
                           routes=["private-do-not-retain"], nativeHostID="private-do-not-retain"))
                          for i in range(320) for boundary in trace.BOUNDARIES)
        values = trace.retained_tail(io.BytesIO(stream))
        self.assertEqual(1024, len(values))
        self.assertEqual([(i, boundary) for i in range(64, 320) for boundary in trace.BOUNDARIES],
                         [(value["commits"], value["boundary"]) for value in values])
        self.assertNotIn("private", json.dumps(values))
        self.assertTrue(all(set(value) == set(trace.NUMERIC + trace.BOOLEAN) | {"boundary"} for value in values))

    def test_overflow_malformed_fields_and_nonprefix_logs_cannot_displace_tail(self):
        valid = wire(row(commits=42))
        bad = [b"private log\n", trace.PREFIX + b"x" * 9000 + b"\n", trace.PREFIX + b"{\n",
               wire(row(boundary=[])), wire(row(boundary={"private": "text"})),
               wire(row(events=True)), wire(row(events=0)), wire(row(events=257)),
               wire(row(commits=-1)), wire(row(commits=2**31)), wire(row(nativeStateChanged=1)),
               wire(row(ownedRegistrations="private")), trace.PREFIX + b"\xff\n"]
        self.assertEqual([row(commits=42)], trace.retained_tail(io.BytesIO(valid + b"".join(bad))))

    def test_unterminated_and_deep_json_are_refused_without_raw_error_output(self):
        nested = trace.PREFIX + b"[" * 1500 + b"0" + b"]" * 1500 + b"\n"
        self.assertEqual([], trace.retained_tail(io.BytesIO(nested + wire(row()).rstrip(b"\n"))))

    def test_optional_counters_are_allowlisted_without_text(self):
        value = row(ownedRegistrations=12, ownedProtectedRoutes=2, error="private")
        projected = trace.project(value)
        self.assertEqual(12, projected["ownedRegistrations"])
        self.assertEqual(2, projected["ownedProtectedRoutes"])
        self.assertNotIn("error", projected)


def producer_row(**changes):
    value = {key: 1 for key in trace.PRODUCER_NUMERIC}
    value.update({key: False for key in trace.PRODUCER_BOOLEAN})
    return {**value, "presentationStatus": "none", **changes}


def producer_wire(value):
    return trace.PRODUCER_PREFIX + json.dumps(value).encode() + b"\n"


class NavigationProducerTraceTests(unittest.TestCase):
    def test_latest_producer_tail_is_independent_of_original_four_removal_tails(self):
        stream = b"".join(producer_wire(producer_row(commits=i, events=i % 256 + 1)) +
                          wire(row(commits=i, events=i % 256 + 1)) for i in range(320))
        values = trace.retained_tail(io.BytesIO(stream))
        self.assertEqual(512, len(values))
        self.assertEqual([(i, kind) for i in range(64, 320) for kind in ("producer", "removal")],
                         [(v["commits"], "removal" if "boundary" in v else "producer") for v in values])

    def test_projects_only_public_closed_scalar_vocabulary(self):
        value = producer_row(snapshotRevision=27, presentationSerial=9, presentationStatus="accepted",
                             privateText="do-not-retain", command={"input": "do-not-retain"}, nativeHostID="do-not-retain")
        projected = trace.project_producer(value)
        self.assertEqual(27, projected["snapshotRevision"])
        self.assertEqual(9, projected["presentationSerial"])
        self.assertEqual("accepted", projected["presentationStatus"])
        self.assertEqual(set(trace.PRODUCER_NUMERIC + trace.PRODUCER_BOOLEAN) | {"presentationStatus"}, set(projected))
        self.assertNotIn("do-not-retain", json.dumps(projected))

    def test_refuses_missing_overflow_boolean_coercion_and_unknown_status(self):
        for field in trace.PRODUCER_NUMERIC:
            for invalid in (-1, 2147483648, True, "private", None):
                with self.subTest(field=field, invalid=type(invalid).__name__):
                    self.assertIsNone(trace.project_producer(producer_row(**{field: invalid})))
            omitted = producer_row(); del omitted[field]
            self.assertIsNone(trace.project_producer(omitted))
        for field in trace.PRODUCER_BOOLEAN:
            self.assertIsNone(trace.project_producer(producer_row(**{field: 1})))
        for status in ([], {}, True, "private", None):
            self.assertIsNone(trace.project_producer(producer_row(presentationStatus=status)))
        for event in (0, 257):
            self.assertIsNone(trace.project_producer(producer_row(events=event)))

    def test_malformed_oversized_unterminated_and_unrelated_input_cannot_displace_valid_tail(self):
        valid = producer_row(renders=42)
        bad = [trace.PRODUCER_PREFIX + b"x" * 9000 + b"\n", trace.PRODUCER_PREFIX + b"\xff\n",
               trace.PRODUCER_PREFIX + b"{\n", b"unrelated " + producer_wire(valid),
               trace.PRODUCER_PREFIX + b"[" * 1500 + b"0" + b"]" * 1500 + b"\n",
               producer_wire(producer_row(presentationStatus="private")), producer_wire(valid).rstrip(b"\n")]
        self.assertEqual([valid], trace.retained_tail(io.BytesIO(producer_wire(valid) + b"".join(bad))))

    def test_saturated_command_counts_and_all_fixed_statuses_are_admitted(self):
        for status in trace.PRODUCER_STATUSES:
            value = producer_row(presentationStatus=status, commands=2147483647, otherCommands=2147483647)
            self.assertEqual(value, trace.project_producer(value))
