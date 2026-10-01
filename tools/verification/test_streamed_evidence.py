import base64
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

from run_android_smoke import run_smoke
from streamed_evidence import CHUNK_BYTES, PREFIX, extract_streamed_evidence
from verify_instrumentation import EXPECTED


def reports(run_id):
    xml = ET.Element('testsuite', runId=run_id, tests=str(len(EXPECTED)), failures='0', skipped='0')
    for name, method in sorted(EXPECTED):
        ET.SubElement(xml, 'testcase', classname=name, name=method)
    return {'smoke-tests.xml': ET.tostring(xml),
            'ordinary-3d-screen.png': b'\x89PNG\r\n\x1a\n' + b'pixel transport' * 3000,
            'ordinary-3d-visibility.json': json.dumps({'runId': run_id, 'visible': True}).encode()}


def packets(files, run_id):
    data = []
    for name, content in files.items():
        count = (len(content) + CHUNK_BYTES - 1) // CHUNK_BYTES
        for index in range(count):
            data.append(dict(schema=1, runId=run_id, file=name, bytes=len(content),
                             sha256=hashlib.sha256(content).hexdigest(), index=index, count=count,
                             data=base64.b64encode(content[index * CHUNK_BYTES:(index + 1) * CHUNK_BYTES]).decode()))
    return data


def transcript(data):
    return ''.join(PREFIX + json.dumps(item) + '\n' for item in data)


class StreamedEvidenceTest(unittest.TestCase):
    def reject(self, data, message):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            log = output / 'instrumentation.txt'
            log.write_text(transcript(data))
            with self.assertRaisesRegex(ValueError, message):
                extract_streamed_evidence(log, output, 'current-run')
            self.assertFalse((output / 'smoke-tests.xml').exists())

    def test_multichunk_files_recover_byte_identically(self):
        files = reports('current-run')
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            log = output / 'instrumentation.txt'
            log.write_text(transcript(packets(files, 'current-run')))
            self.assertEqual(set(files), extract_streamed_evidence(log, output, 'current-run'))
            for name, data in files.items():
                self.assertEqual(data, (output / name).read_bytes())

    def test_stale_run_is_rejected_before_writing(self):
        self.reject(packets(reports('old-run'), 'old-run'), 'Unidentified')

    def test_missing_final_chunk_is_rejected_before_writing(self):
        data = packets(reports('current-run'), 'current-run')
        self.reject([item for item in data if not (item['file'].endswith('.png') and item['index'] == 1)], 'Incomplete')

    def test_same_length_corruption_is_rejected_by_hash(self):
        data = packets(reports('current-run'), 'current-run')
        chunk = base64.b64decode(data[0]['data'])
        data[0]['data'] = base64.b64encode(b'X' + chunk[1:]).decode()
        self.reject(data, 'hash mismatch')

    def test_xml_without_real_screen_file_is_not_accepted(self):
        files = reports('current-run')
        del files['ordinary-3d-screen.png']
        self.reject(packets(files, 'current-run'), 'Incomplete')

    def test_finished_runner_keeps_all_gates_when_new_adb_connections_fail(self):
        files = reports('current-run')
        text = transcript(packets(files, 'current-run')) + 'OK (11 tests)\n'
        def adb(command, **kwargs):
            if 'instrument' in command:
                kwargs['stdout'].write(text.encode())
                return subprocess.CompletedProcess(command, 0)
            if 'logcat' in command and '-c' in command or 'rm' in command:
                return subprocess.CompletedProcess(command, 0)
            raise subprocess.TimeoutExpired(command, 15)
        def collector(command, **kwargs):
            kwargs['stdout'].write(b'actual live logcat bytes before runner finish\n')
            return Mock(poll=Mock(return_value=None), wait=Mock(return_value=-15))
        with tempfile.TemporaryDirectory() as directory, patch('run_android_smoke.subprocess.run', side_effect=adb), patch('run_android_smoke.subprocess.Popen', side_effect=collector):
            run_smoke(directory, 'current-run')
            status = json.loads((Path(directory) / 'runner-status.json').read_text())
            self.assertEqual('instrumentation-stream-sha256', status['captures']['smoke-tests.xml']['source'])
            self.assertEqual('live-logcat', status['captures']['logcat.txt']['source'])
            for name, data in files.items():
                self.assertEqual(data, (Path(directory) / name).read_bytes())

    def test_failed_case_still_fails_after_successful_stream_transfer(self):
        files = reports('current-run')
        xml = ET.fromstring(files['smoke-tests.xml'])
        ET.SubElement(xml.find('testcase'), 'failure', message='actual assertion failed')
        files['smoke-tests.xml'] = ET.tostring(xml)
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            log = output / 'instrumentation.txt'
            log.write_text(transcript(packets(files, 'current-run')))
            extract_streamed_evidence(log, output, 'current-run')
            from verify_instrumentation import verify
            with self.assertRaisesRegex(ValueError, 'failed or skipped'):
                verify(output / 'smoke-tests.xml', 'current-run')
