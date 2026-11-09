import json
import re
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import android_default_app_recovery as recovery
import android_emulator_evidence as evidence

ROOT = Path(__file__).resolve().parents[1]
OPTIONAL_BACKUP = "com.lavasec.app.backup.BackupProductionJourneyInstrumentationTest#passkeyPrfSeamDecryptsAdoptsAndPreservesEveryPortableSlot"


def bundle(key, code=1):
    name, method = key.split("#", 1)
    return f"INSTRUMENTATION_STATUS: class={name}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: {code}\n"


class DefaultAppRecoveryTests(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory(); self.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name)
        self.original = self.root / "app.txt"; self.xml = self.root / "app.xml"
        self.original.write_text("INSTRUMENTATION_CODE: 0\n")
        self.xml.write_text('<testsuite><testcase classname="runner" name="completed"><error/></testcase></testsuite>')
        self.plan = self.root / "plan.json"; self.ledger = self.root / "attempts.json"
        self.a, self.b, self.c, self.d = recovery.DEFAULT_CASES[:4]

    def save(self):
        value = recovery.make_plan(self.root, self.original, self.xml)
        recovery.write_json(self.plan, value)
        return value

    def test_exact_73_default_methods_exist_only_in_actual_app_test_apk_sources(self):
        app, vpn, nonrequired = set(), set(), set()
        for module, classes in (("app", app), ("vpn", vpn)):
            for path in (ROOT / f"android/{module}/src/androidTest").rglob("*.kt"):
                source = path.read_text()
                package = re.search(r"(?m)^package ([\w.]+)", source)
                if package:
                    classes.update(package[1] + "." + name for name in re.findall(r"(?m)^(?:internal )?(?:open )?class (\w+)", source))
                    actual_class = re.search(r"(?m)^(?:internal )?(?:open )?class (\w+)", source)
                    name = package[1] + "." + actual_class[1] if actual_class else None
                    if module == "app" and name in recovery.DEFAULT_CLASSES:
                        tests = re.findall(r"@Test\s+(?:@[\w.]+(?:\([^\n]*\))?\s+)*(?:suspend )?fun (\w+)", source)
                        nonrequired.update(name + "#" + method for method in tests if name + "#" + method not in recovery.DEFAULT_SET)
        self.assertEqual(73, len(recovery.DEFAULT_CASES)); self.assertEqual(73, len(recovery.DEFAULT_SET))
        self.assertTrue(recovery.DEFAULT_CLASSES <= app)
        self.assertFalse(recovery.DEFAULT_CLASSES & vpn)
        self.assertFalse(recovery.DEFAULT_CLASSES & recovery.EXCLUDED)
        self.assertFalse(any(name.startswith("com.lavasec.app.vpn.") for name in recovery.DEFAULT_CLASSES))
        self.assertEqual(112, sum(map(len, evidence.REQUIRED_CASES.values())) + len(evidence.PROCESS_PHASES))
        self.assertEqual({OPTIONAL_BACKUP}, nonrequired,
                         "The reviewed non-required exception must remain a real @Test, not a required or invented method")
        self.assertEqual(nonrequired, recovery.KNOWN_NON_REQUIRED_CASES)
        self.assertTrue(nonrequired.isdisjoint(recovery.DEFAULT_SET))

    def test_advertised_crashing_failed_passed_and_skipped_cases_never_enter_plan(self):
        self.original.write_text(bundle(self.a) + bundle(self.b, 0) + bundle(self.c, -2) + bundle(self.d, -3))
        value = self.save()
        self.assertTrue({self.a, self.b, self.c, self.d}.isdisjoint(value["selectors"]))
        self.assertEqual(len(recovery.DEFAULT_CASES) - 4, len(value["selectors"]))

    def test_known_nonrequired_raw_method_does_not_advertise_required_siblings(self):
        # A complete real but non-required method does not advertise its nine
        # required siblings. START/terminal/duplicate and complete EOF framing
        # must leave exactly those never-advertised methods eligible.
        name = OPTIONAL_BACKUP.split("#")[0]
        siblings = {key for key in recovery.DEFAULT_CASES if key.startswith(name + "#")}
        self.assertEqual(9, len(siblings))
        for code in (1, 0, -1, -2, -3):
            with self.subTest(nonrequired_status=code):
                self.original.write_text(bundle(OPTIONAL_BACKUP, code) * 2 + bundle(self.a))
                selectors = set(self.save()["selectors"])
                self.assertTrue(siblings <= selectors)
                self.assertNotIn(self.a, selectors)
                self.assertNotIn(OPTIONAL_BACKUP, selectors)
        self.original.write_text(bundle(OPTIONAL_BACKUP).rsplit("INSTRUMENTATION_STATUS_CODE:", 1)[0])
        self.assertTrue(siblings <= set(self.save()["selectors"]))

    def test_ambiguous_partial_start_suppresses_class_and_unidentified_start_suppresses_all(self):
        name = self.a.split("#")[0]
        self.assertTrue({key for key in recovery.DEFAULT_CASES if key.startswith(name + "#")} <=
                        recovery.advertised(f"INSTRUMENTATION_STATUS: class={name}\nINSTRUMENTATION_STATUS_CODE: 1\n"))
        self.assertEqual(recovery.DEFAULT_SET, recovery.advertised("INSTRUMENTATION_STATUS_CODE: 1\n"))
        for ambiguous_class in (name, OPTIONAL_BACKUP.split("#", 1)[0]):
            siblings = {key for key in recovery.DEFAULT_CASES if key.startswith(ambiguous_class + "#")}
            for transcript in (bundle(ambiguous_class + "#unknownMethod"),
                               "INSTRUMENTATION_STATUS: class=" + ambiguous_class + "\n",
                               "INSTRUMENTATION_STATUS: class=" + ambiguous_class + "\nINSTRUMENTATION_STATUS: class=" + ambiguous_class + "\nINSTRUMENTATION_STATUS: test=unknownMethod\n"):
                with self.subTest(ambiguous=transcript):
                    self.assertTrue(siblings <= recovery.advertised(transcript))
        method_only = "INSTRUMENTATION_STATUS: test=" + OPTIONAL_BACKUP.split("#", 1)[1] + "\n"
        self.assertEqual(recovery.DEFAULT_SET, recovery.advertised(method_only))
        with self.assertRaises(recovery.RecoveryError):
            recovery.advertised("INSTRUMENTATION_STATUS: class=private invalid class\nINSTRUMENTATION_STATUS_CODE: 1\n")

    def test_terminal_xml_excludes_all_statuses_and_claim_is_once_even_without_native_progress(self):
        tree = ET.parse(self.xml)
        for key, status in ((self.a, None), (self.b, "failure"), (self.c, "skipped")):
            name, method = key.split("#")
            case = ET.SubElement(tree.getroot(), "testcase", classname=name, name=method)
            if status: ET.SubElement(case, status)
        tree.write(self.xml)
        value = self.save()
        self.assertTrue({self.a, self.b, self.c}.isdisjoint(value["selectors"]))
        self.assertTrue(recovery.claim(self.plan, self.original, self.xml, self.root, self.ledger, self.d))
        self.assertFalse(recovery.claim(self.plan, self.original, self.xml, self.root, self.ledger, self.d))
        self.assertEqual([self.d], json.loads(self.ledger.read_text()))

    def test_known_nonrequired_xml_method_preserves_required_siblings_but_unknown_suppresses(self):
        name, method = OPTIONAL_BACKUP.split("#", 1)
        siblings = {key for key in recovery.DEFAULT_CASES if key.startswith(name + "#")}
        for status in (None, "failure", "error", "skipped"):
            with self.subTest(nonrequired_xml=status):
                suite = ET.Element("testsuite")
                case = ET.SubElement(suite, "testcase", classname=name, name=method)
                if status: ET.SubElement(case, status)
                path = self.root / "known-nonrequired.xml"
                ET.ElementTree(suite).write(path)
                self.assertTrue(siblings.isdisjoint(recovery.xml_observed(self.root)))
                ET.SubElement(suite, "testcase", classname=name, name="unknownMethod")
                ET.ElementTree(suite).write(path)
                self.assertTrue(siblings <= recovery.xml_observed(self.root))
                path.unlink()

    def test_current_recovery_advertisements_and_terminals_prevent_later_selector(self):
        self.save()
        (self.root / "recovery-001.txt").write_text(bundle(self.a))
        name, method = self.b.split("#")
        (self.root / "recovery-001.xml").write_text(f'<testsuite><testcase classname="{name}" name="{method}"><skipped/></testcase></testsuite>')
        for key in (self.a, self.b):
            self.assertFalse(recovery.claim(self.plan, self.original, self.xml, self.root, self.ledger, key))

    def test_original_hash_change_corrupt_xml_or_nondefault_plan_refuses(self):
        self.save(); self.original.write_text("changed original")
        with self.assertRaises(recovery.RecoveryError): recovery.load_plan(self.plan, self.original, self.xml)
        self.original.write_text("INSTRUMENTATION_CODE: 0\n"); self.save()
        value = json.loads(self.plan.read_text()); value["selectors"].append("private#unknown")
        self.plan.write_text(json.dumps(value))
        with self.assertRaises(recovery.RecoveryError): recovery.load_plan(self.plan, self.original, self.xml)
        (self.root / "corrupt.xml").write_text("invalid XML")
        with self.assertRaises(recovery.RecoveryError): recovery.observed(self.root, self.original)

    def test_all_supplementary_passes_cannot_remove_original_runner_failure(self):
        self.save()
        suite = ET.Element("testsuite")
        for key in recovery.DEFAULT_CASES:
            name, method = key.split("#")
            ET.SubElement(suite, "testcase", classname=name, name=method)
        ET.ElementTree(suite).write(self.root / "recovery-001.xml")
        cases = evidence.results_under(self.root)
        self.assertEqual("fail", cases["runner#completed"])
        self.assertTrue(all(cases[key] == "pass" for key in recovery.DEFAULT_CASES))
        self.assertIn("API 28: failed runner#completed", evidence.violations([{"api": 28, "cases": cases}]))

    def test_passed_original_does_not_authorize_missing_case_recovery(self):
        self.xml.write_text('<testsuite><testcase classname="runner" name="completed"/></testsuite>')
        self.assertEqual([], self.save()["selectors"])
