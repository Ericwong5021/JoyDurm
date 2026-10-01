"""Recover exact test-owned files before instrumentation/ADB teardown, never synthesize them."""
import base64
import hashlib
import json
import re
from pathlib import Path

REQUIRED = {'smoke-tests.xml', 'ordinary-3d-screen.png', 'ordinary-3d-visibility.json'}
ALLOWED = REQUIRED | {'thread-stalls.txt'}
PREFIX = 'INSTRUMENTATION_STATUS: joydurmEvidence='
CHUNK_BYTES = 12_288
MAX_BYTES = 8_388_608


def extract_streamed_evidence(instrumentation, output, run_id):
    files = {}
    with Path(instrumentation).open() as stream:
        for line in stream:
            if not line.startswith(PREFIX):
                continue
            item = json.loads(line[len(PREFIX):])
            name = item.get('file')
            if item.get('schema') != 1 or item.get('runId') != run_id or name not in ALLOWED:
                raise ValueError('Unidentified or unexpected streamed evidence')
            size, count, index = (item.get(key) for key in ('bytes', 'count', 'index'))
            digest = item.get('sha256')
            if (any(type(value) is not int for value in (size, count, index)) or
                    not 1 <= size <= MAX_BYTES or count != (size + CHUNK_BYTES - 1) // CHUNK_BYTES or
                    not 0 <= index < count or not isinstance(digest, str) or
                    not re.fullmatch('[0-9a-f]{64}', digest)):
                raise ValueError('Invalid streamed evidence bounds')
            metadata = (size, count, digest)
            entry = files.setdefault(name, (metadata, {}))
            if entry[0] != metadata or index in entry[1]:
                raise ValueError('Conflicting or duplicated streamed evidence')
            try:
                chunk = base64.b64decode(item['data'], validate=True)
            except (ValueError, TypeError, KeyError) as error:
                raise ValueError('Invalid streamed evidence encoding') from error
            if len(chunk) != min(CHUNK_BYTES, size - index * CHUNK_BYTES):
                raise ValueError('Truncated streamed evidence chunk')
            entry[1][index] = chunk
    if not files:
        return set()  # Older test APKs still require their actual post-run files.
    if not REQUIRED <= files.keys():
        raise ValueError('Incomplete streamed evidence file set')
    recovered = {}
    for name, ((size, count, digest), chunks) in files.items():
        if len(chunks) != count:
            raise ValueError('Incomplete streamed evidence chunks')
        data = b''.join(chunks[index] for index in range(count))
        if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
            raise ValueError('Streamed evidence hash mismatch')
        recovered[name] = data
    # Validate the whole collection before writing any file. The caller still checks
    # all eleven XML cases, the run ID, runner completion and real display metrics.
    for name, data in recovered.items():
        (Path(output) / name).write_bytes(data)
    return set(recovered)
