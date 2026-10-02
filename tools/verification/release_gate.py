#!/usr/bin/env python3
"""Fail closed on release identity mismatches; no signing keys are read here."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def version_from_gradle(text):
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r'versionCode\s*=\s*(\d+)', text)
    if not name or not code or int(code[1]) < 1:
        raise ValueError("Missing literal, positive Android version identity")
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?', name[1]):
        raise ValueError("versionName must be a semantic version")
    return dict(versionName=name[1], versionCode=int(code[1]))


def verify_tag(tag, version):
    if tag != 'v' + version['versionName']:
        raise ValueError("Release tag must exactly match v<versionName>")


def verify_version_progress(version, baseline):
    if version['versionCode'] <= baseline['versionCode']:
        raise ValueError("Release versionCode must increase beyond the reviewed baseline")
    if version['versionName'] == baseline['versionName']:
        raise ValueError("Release versionName must change from the reviewed baseline")


def certificate_digest(output):
    digests = re.findall(r'^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)\s*$', output, re.M)
    if len(digests) != 1:
        raise ValueError("Exactly one APK signer is required")
    digest = digests[0].replace(':', '').lower()
    if not re.fullmatch('[0-9a-f]{64}', digest):
        raise ValueError("Invalid APK certificate SHA-256 digest")
    return digest


def verify_certificate(actual, expected):
    expected = expected.replace(':', '').strip().lower()
    if not re.fullmatch('[0-9a-f]{64}', expected):
        raise ValueError("Expected certificate SHA-256 must be configured explicitly")
    if actual != expected:
        raise ValueError("APK signer does not match the expected certificate")


def apk_identity(output):
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output, re.M)
    if not package or package[1] != 'ai.joydurm':
        raise ValueError("Unexpected APK application ID")
    return dict(applicationId=package[1], versionCode=int(package[2]), versionName=package[3])


def report(apk, sha, version, aapt, apksigner, expected_certificate=None):
    if not re.fullmatch('[0-9a-f]{40}', sha):
        raise ValueError("Source SHA must be a full Git commit identity")
    identity = apk_identity(subprocess.check_output([aapt, 'dump', 'badging', str(apk)], text=True))
    if any(identity[key] != value for key, value in version.items()):
        raise ValueError("Built APK version differs from the source version")
    digest = certificate_digest(subprocess.check_output([apksigner, 'verify', '--verbose', '--print-certs', str(apk)], text=True))
    if expected_certificate is not None:
        verify_certificate(digest, expected_certificate)
    return dict(schemaVersion=1, sourceSha=sha, apk=apk.name,
                apkSha256=hashlib.sha256(apk.read_bytes()).hexdigest(), bytes=apk.stat().st_size,
                certificateSha256=digest, **identity,
                evidenceScope='Build identity only; see separate installation and hardware reports')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle', type=Path, default=Path('app/build.gradle.kts'))
    parser.add_argument('--tag')
    parser.add_argument('--baseline', type=Path, default=Path('docs/release-baseline.json'))
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--source-sha')
    parser.add_argument('--aapt')
    parser.add_argument('--apksigner')
    parser.add_argument('--expected-certificate')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    version = version_from_gradle(args.gradle.read_text())
    if args.tag:
        verify_tag(args.tag, version)
        verify_version_progress(version, json.loads(args.baseline.read_text()))
    if args.apk:
        if not all((args.source_sha, args.aapt, args.apksigner, args.output)):
            parser.error('--apk requires source SHA, aapt, apksigner and output')
        evidence = report(args.apk, args.source_sha, version, args.aapt, args.apksigner, args.expected_certificate)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(version))


if __name__ == '__main__':
    main()
