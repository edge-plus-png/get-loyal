#!/usr/bin/env python3
"""Verify the pinned shared Counter snapshot without requiring its dirty source checkout."""
from pathlib import Path
import hashlib,json
root=Path(__file__).resolve().parents[1]/'shared-counter'
manifest=json.loads((root/'source-manifest.json').read_text())
for record in manifest['files']:
    path=root/'src/main/kotlin/com/edge/poslite'/record['path']
    actual=hashlib.sha256(path.read_bytes()).hexdigest()
    assert actual==record['sha256'], f'Shared Counter source drift: {record["path"]}'
print(f'PASS: {len(manifest["files"])} shared Counter files exactly match the recorded snapshot.')
