import unittest
import json
import subprocess
import tempfile
from pathlib import Path
from unittest.mock import Mock
import xml.etree.ElementTree as ET

from ci.android_minified_startup import capture_evidence, labels, tap_point, visible_nodes


class MinifiedStartupObservationTest(unittest.TestCase):
    def test_capture_failure_retains_original_receipt_and_attempts_each_diagnostic(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)
            receipt = '{"status":"pass"}\n'
            (evidence / 'result.json').write_text(receipt)
            adb = Mock(side_effect=[subprocess.CalledProcessError(1, ['adb']), b'png'])
            capture_evidence(evidence, adb, '42', 'start')
            self.assertEqual(2, adb.call_count)
            self.assertEqual(b'png', (evidence / 'last-screen.png').read_bytes())
            self.assertEqual(receipt, (evidence / 'result.json').read_text())
            self.assertEqual(['crash.log'], list(json.loads((evidence / 'capture-errors.json').read_text())))

    def test_capture_cannot_mask_the_original_launch_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, 'original launch failure'):
                try:
                    raise ValueError('original launch failure')
                finally:
                    capture_evidence(Path(directory), Mock(side_effect=OSError('device gone')), '42', 'start')

    def test_observes_and_targets_the_rendered_app_control(self):
        xml = '''<hierarchy><node package="com.lavasec.app" text="Meet Lava"
                 enabled="true" bounds="[20,100][220,180]" /></hierarchy>'''
        nodes = visible_nodes(xml, "com.lavasec.app")
        self.assertIn("Meet Lava", labels(nodes))
        self.assertEqual((120, 140), tap_point(nodes, "Meet Lava"))
        self.assertEqual((120, 140), tap_point(nodes, "Meet lava"))

    def test_another_app_or_empty_screen_cannot_pass_as_a_successful_launch(self):
        for xml in ('<hierarchy/>', '<hierarchy><node package="com.android.settings" text="Guard" /></hierarchy>'):
            with self.subTest(xml=xml), self.assertRaises(ValueError):
                visible_nodes(xml, "com.lavasec.app")
        with self.assertRaises(ET.ParseError):
            visible_nodes("not XML", "com.lavasec.app")

    def test_disabled_or_unrendered_controls_cannot_be_tapped(self):
        for attributes in ({"enabled": "false", "bounds": "[20,100][220,180]"},
                           {"enabled": "true", "bounds": "[0,0][0,0]"}):
            with self.subTest(attributes=attributes), self.assertRaises(ValueError):
                tap_point([{"text": "Meet Lava", **attributes}], "Meet Lava")

    def test_a_tab_is_preferred_over_the_identically_named_heading(self):
        nodes = [
            {"text": "Settings", "enabled": "true", "clickable": "false", "bounds": "[20,20][220,80]"},
            {"content-desc": "Settings", "enabled": "true", "clickable": "true", "bounds": "[200,800][400,900]"},
        ]
        self.assertEqual((300, 850), tap_point(nodes, "Settings"))
