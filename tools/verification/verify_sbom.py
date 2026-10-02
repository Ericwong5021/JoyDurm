#!/usr/bin/env python3
"""Check runtime SBOM identity, digest coverage and graph referential integrity."""
import argparse
import json
import re


def verify(bom, expected_source=None):
    if bom.get('bomFormat') != 'CycloneDX' or bom.get('specVersion') != '1.5':
        raise ValueError('Expected CycloneDX 1.5 runtime inventory')
    components = bom.get('components', [])
    refs = [component['bom-ref'] for component in components]
    if not components or len(refs) != len(set(refs)):
        raise ValueError('Empty or duplicated SBOM component inventory')
    app_ref = bom['metadata']['component']['bom-ref']
    inventory = {*refs, app_ref}
    for component in components:
        if not component.get('version') or component.get('purl') != component['bom-ref']:
            raise ValueError('Unversioned dependency identity')
        properties = {p['name']: p['value'] for p in component.get('properties', [])}
        metadata_only = properties.get('joydurm:artifact') == 'Resolved metadata/platform component; no packaged runtime artifact'
        hashes = component.get('hashes', [])
        if not metadata_only and not any(item['alg'] == 'SHA-256' and re.fullmatch('[0-9a-f]{64}', item['content']) for item in hashes):
            raise ValueError('Packaged runtime artifact has no SHA-256')
    for edge in bom.get('dependencies', []):
        if edge['ref'] not in inventory or any(ref not in inventory for ref in edge['dependsOn']):
            raise ValueError('SBOM graph references a missing component')
    if expected_source:
        properties = {p['name']: p['value'] for p in bom['metadata'].get('properties', [])}
        if properties.get('joydurm:source-sha') != expected_source:
            raise ValueError('SBOM source identity differs from the build')
    return len(components)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sbom')
    parser.add_argument('--source-sha')
    args = parser.parse_args()
    with open(args.sbom) as stream:
        count = verify(json.load(stream), args.source_sha)
    print(f'PASS: {count} versioned runtime/metadata components; license review remains separately documented')
