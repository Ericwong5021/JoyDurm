import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from release_gate import apk_identity, certificate_digest, verify_certificate, verify_tag, verify_version_progress, version_from_gradle
from verify_assets import verify


class ReleaseGateTest(unittest.TestCase):
    def test_tag_must_match_literal_app_version(self):
        version = version_from_gradle('versionCode = 3; versionName = "0.1.2"')
        verify_tag('v0.1.2', version)
        for tag in ('v0.1.1', 'v0.1.2-extra', '0.1.2'):
            with self.assertRaises(ValueError):
                verify_tag(tag, version)

    def test_version_code_must_be_positive(self):
        with self.assertRaises(ValueError):
            version_from_gradle('versionCode = 0; versionName = "0.1.2"')

    def test_release_cannot_reuse_or_lower_reviewed_version(self):
        baseline = dict(versionCode=2, versionName='0.1.1')
        verify_version_progress(dict(versionCode=3, versionName='0.2.0'), baseline)
        for version in (dict(versionCode=2, versionName='0.2.0'), dict(versionCode=1, versionName='0.2.0'), dict(versionCode=3, versionName='0.1.1')):
            with self.assertRaises(ValueError):
                verify_version_progress(version, baseline)

    def test_certificate_missing_wrong_or_multiple_signers_rejected(self):
        actual = certificate_digest('Signer #1 certificate SHA-256 digest: ' + 'ab' * 32)
        verify_certificate(actual, 'AB:' * 31 + 'AB')
        for expected in ('', 'a' * 63, 'cd' * 32):
            with self.assertRaises(ValueError):
                verify_certificate(actual, expected)
        with self.assertRaises(ValueError):
            certificate_digest('Signer #1 certificate SHA-256 digest: ' + 'ab' * 32 + '\nSigner #2 certificate SHA-256 digest: ' + 'ab' * 32)

    def test_wrong_application_id_rejected(self):
        self.assertEqual(3, apk_identity("package: name='ai.joydurm' versionCode='3' versionName='0.1.2'")['versionCode'])
        with self.assertRaises(ValueError):
            apk_identity("package: name='other.app' versionCode='3' versionName='0.1.2'")


class AssetGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.assets = self.root / 'app/src/main/assets'
        self.assets.mkdir(parents=True)
        (self.assets / 'drum.bin').write_bytes(b'original')
        (self.root / 'LICENSE').write_text('Redistribution permission fixture')
        self.manifest = dict(assets=[dict(path='drum.bin', author='Author', source='https://example.invalid/original',
            sourceVersion='1', license='MIT', licenseEvidence='LICENSE', modifications='None',
            sha256=hashlib.sha256(b'original').hexdigest())])

    def tearDown(self):
        self.temporary.cleanup()

    def test_packaged_bytes_and_inventory_match_reviewed_assets(self):
        apk = self.root / 'fixture.apk'
        with zipfile.ZipFile(apk, 'w') as archive:
            archive.writestr('assets/drum.bin', b'original')
        self.assertEqual(1, verify(self.root, self.manifest, apk)['assetCount'])

    def test_missing_permission_changed_bytes_or_unlisted_asset_rejected(self):
        missing = copy.deepcopy(self.manifest)
        missing['assets'][0]['licenseEvidence'] = ''
        with self.assertRaises(ValueError):
            verify(self.root, missing)
        (self.assets / 'drum.bin').write_bytes(b'changed')
        with self.assertRaises(ValueError):
            verify(self.root, self.manifest)
        (self.assets / 'drum.bin').write_bytes(b'original')
        (self.assets / 'unreviewed.bin').write_bytes(b'new')
        with self.assertRaises(ValueError):
            verify(self.root, self.manifest)

    def test_apk_added_resource_or_changed_resource_rejected(self):
        apk = self.root / 'fixture.apk'
        for resources in ({'drum.bin': b'altered'}, {'drum.bin': b'original', 'unknown.wav': b'unlicensed'}):
            with zipfile.ZipFile(apk, 'w') as archive:
                for path, data in resources.items():
                    archive.writestr('assets/' + path, data)
            with self.assertRaises(ValueError):
                verify(self.root, self.manifest, apk)

    def test_traversal_cannot_be_used_as_license_evidence(self):
        self.manifest['assets'][0]['licenseEvidence'] = '../LICENSE'
        with self.assertRaises(ValueError):
            verify(self.root, self.manifest)

    def test_dependency_assets_are_exact_and_license_archive_hashes_are_required(self):
        license_hash = hashlib.sha256((self.root / 'LICENSE').read_bytes()).hexdigest()
        dependency = dict(path='materials/fixture.filamat', author='Upstream', source='maven:org.test:fixture:1!/assets/materials/fixture.filamat',
            sourceVersion='1', sourceArchiveSha256='ab' * 32, license='MIT', licenseEvidence='LICENSE',
            licenseEvidenceSha256=license_hash, modifications='Unmodified', sha256=hashlib.sha256(b'material').hexdigest())
        self.manifest['dependencyAssets'] = [dependency]
        apk = self.root / 'fixture.apk'
        with zipfile.ZipFile(apk, 'w') as archive:
            archive.writestr('assets/drum.bin', b'original')
            archive.writestr('assets/materials/fixture.filamat', b'material')
        self.assertEqual(1, verify(self.root, self.manifest, apk)['dependencyAssetCount'])
        dependency['sourceArchiveSha256'] = ''
        with self.assertRaises(ValueError):
            verify(self.root, self.manifest, apk)
        dependency['sourceArchiveSha256'] = 'ab' * 32
        (self.root / 'LICENSE').write_text('Changed permission')
        with self.assertRaises(ValueError):
            verify(self.root, self.manifest, apk)

    def test_generated_profiles_are_exact_paths_and_final_hashes_are_enforced(self):
        generated = dict(path='dexopt/baseline.prof', author='Compiler', source='AGP profile generation',
            sourceVersion='8.9.1', license='MIT', licenseEvidence='LICENSE', modifications='Generated')
        self.manifest['generatedAssets'] = [generated]
        apk = self.root / 'fixture.apk'
        with zipfile.ZipFile(apk, 'w') as archive:
            archive.writestr('assets/drum.bin', b'original')
            archive.writestr('assets/dexopt/baseline.prof', b'profile')
        final = self.root / 'final.json'
        verify(self.root, self.manifest, apk, final)
        resolved = json.loads(final.read_text())
        self.assertEqual(hashlib.sha256(b'profile').hexdigest(), resolved['generatedAssets'][0]['sha256'])
        verify(self.root, resolved, apk)
        resolved['generatedAssets'][0]['sha256'] = '00' * 32
        with self.assertRaises(ValueError):
            verify(self.root, resolved, apk)
        generated['path'] = 'dexopt/unreviewed.wav'
        with self.assertRaises(ValueError):
            verify(self.root, self.manifest, apk)


class ActionPinTest(unittest.TestCase):
    def test_every_workflow_action_matches_verified_commit(self):
        import re
        root = Path(__file__).resolve().parents[2]
        pins = {item['repository'].lower(): item['commit'] for item in json.loads((root / 'docs/action-pins.json').read_text())['actions']}
        uses = re.findall(r'uses:\s*([^@\s]+)@([^\s]+)', (root / '.github/workflows/android.yml').read_text())
        self.assertTrue(uses)
        for action, commit in uses:
            repository = '/'.join(action.split('/')[:2]).lower()
            self.assertRegex(commit, r'^[0-9a-f]{40}$')
            self.assertEqual(pins[repository], commit)


if __name__ == '__main__':
    unittest.main()
