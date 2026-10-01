#!/usr/bin/env python3
"""Verify reviewed asset hashes and redistribution evidence, including APK bytes."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def safe_path(value):
    path = PurePosixPath(value)
    if not value or path.is_absolute() or '..' in path.parts or '\\' in value:
        raise ValueError('Unsafe asset path')
    return value


def verify(root, manifest, apk=None, final_manifest=None):
    assets = root / 'app/src/main/assets'
    records = manifest.get('assets', [])
    required = ('path', 'author', 'source', 'sourceVersion', 'license', 'licenseEvidence', 'modifications', 'sha256')
    indexed = {}
    for record in records:
        if any(not isinstance(record.get(key), str) or not record[key].strip() for key in required):
            raise ValueError('Asset is missing provenance or redistribution evidence')
        path = safe_path(record['path'])
        if path in indexed:
            raise ValueError('Duplicate asset identity')
        if not re.fullmatch('[0-9a-f]{64}', record['sha256']):
            raise ValueError('Invalid reviewed asset digest')
        evidence = root / safe_path(record['licenseEvidence'])
        if not evidence.is_file() or not evidence.read_bytes():
            raise ValueError('Redistribution evidence does not exist')
        actual = hashlib.sha256((assets / path).read_bytes()).hexdigest()
        if actual != record['sha256']:
            raise ValueError('Asset changed without manifest review: ' + path)
        indexed[path] = record
    on_disk = {p.relative_to(assets).as_posix() for p in assets.rglob('*') if p.is_file()}
    if on_disk != set(indexed):
        raise ValueError('Asset inventory differs: ' + str(sorted(on_disk.symmetric_difference(indexed))))
    dependency = {}
    for record in manifest.get('dependencyAssets', []):
        if any(not isinstance(record.get(key), str) or not record[key].strip() for key in required):
            raise ValueError('Dependency asset is missing provenance or redistribution evidence')
        path = safe_path(record['path'])
        if path in indexed or path in dependency:
            raise ValueError('Dependency asset collides with an app asset or another dependency')
        if not re.fullmatch('[0-9a-f]{64}', record['sha256']) or not re.fullmatch('[0-9a-f]{64}', record.get('sourceArchiveSha256', '')):
            raise ValueError('Dependency asset needs fixed content and AAR digests')
        evidence = root / safe_path(record['licenseEvidence'])
        if not evidence.is_file() or hashlib.sha256(evidence.read_bytes()).hexdigest() != record.get('licenseEvidenceSha256'):
            raise ValueError('Dependency license evidence differs from the reviewed upstream license')
        dependency[path] = record
    packaged_inventory = {**indexed, **dependency}
    generated = {}
    for record in manifest.get('generatedAssets', []):
        path = safe_path(record.get('path', ''))
        if path not in ('dexopt/baseline.prof', 'dexopt/baseline.profm') or path in packaged_inventory or path in generated:
            raise ValueError('Unexpected or colliding generated build asset')
        for key in ('author', 'source', 'sourceVersion', 'license', 'licenseEvidence', 'modifications'):
            if not isinstance(record.get(key), str) or not record[key].strip():
                raise ValueError('Generated asset is missing build provenance')
        if not (root / safe_path(record['licenseEvidence'])).is_file():
            raise ValueError('Generated asset license evidence is missing')
        generated[path] = record
    if apk:
        with zipfile.ZipFile(apk) as archive:
            packaged = {name[7:] for name in archive.namelist() if name.startswith('assets/') and not name.endswith('/')}
            media = packaged.difference(generated)
            if media != set(packaged_inventory):
                raise ValueError('APK asset inventory differs; review dependency asset provenance: ' + str(sorted(media.symmetric_difference(packaged_inventory))))
            for path, record in packaged_inventory.items():
                if hashlib.sha256(archive.read('assets/' + path)).hexdigest() != record['sha256']:
                    raise ValueError('Packaged asset mismatch: ' + path)
            resolved = []
            for path, record in generated.items():
                if path in packaged:
                    data = archive.read('assets/' + path)
                    if not data or len(data) > 5_000_000:
                        raise ValueError('Generated baseline profile is empty or exceeds its budget')
                    digest = hashlib.sha256(data).hexdigest()
                    if 'sha256' in record and digest != record['sha256']:
                        raise ValueError('Final generated profile digest changed: ' + path)
                    resolved.append({**record, 'sha256': digest, 'bytes': len(data)})
            if final_manifest:
                result = {**manifest, 'generatedAssets': resolved,
                          'apkSha256': hashlib.sha256(Path(apk).read_bytes()).hexdigest()}
                final_manifest.parent.mkdir(parents=True, exist_ok=True)
                final_manifest.write_text(json.dumps(result, indent=2) + '\n')
    return dict(assetCount=len(indexed), dependencyAssetCount=len(dependency), generatedAssetCount=len(resolved) if apk else None, verifiedApk=str(apk) if apk else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--manifest', type=Path, default=ROOT / 'docs/assets-manifest.json')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--final-manifest', type=Path, help='Resolve build-generated profile hashes into an APK-specific manifest')
    args = parser.parse_args()
    if args.final_manifest and not args.apk:
        parser.error('--final-manifest requires --apk')
    print(json.dumps(verify(args.root, json.loads(args.manifest.read_text()), args.apk, args.final_manifest)))


if __name__ == '__main__':
    main()
