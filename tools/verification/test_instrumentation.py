import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

from verify_instrumentation import EXPECTED, verify


class InstrumentationFreshnessTest(unittest.TestCase):
    def test_old_successful_xml_cannot_validate_a_new_or_unidentified_run(self):
        suite = ET.Element('testsuite', tests=str(len(EXPECTED)), failures='0', errors='0', skipped='0', runId='old-completed-run')
        for classname, name in EXPECTED:
            ET.SubElement(suite, 'testcase', classname=classname, name=name)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'smoke.xml'
            ET.ElementTree(suite).write(path)
            verify(path, 'old-completed-run')
            with self.assertRaisesRegex(ValueError, 'Stale or unidentified'):
                verify(path, 'new-interrupted-run')
            del suite.attrib['runId']
            ET.ElementTree(suite).write(path)
            with self.assertRaisesRegex(ValueError, 'Stale or unidentified'):
                verify(path, 'new-interrupted-run')
