#!/usr/bin/env python3
"""Re-runnable multipart harness. Persists session ID; retries reuse uploaded parts.
Usage: DRIVE_AUTH='Basic ...' python3 docker/transfer.py file [session-state.json]
"""
import base64
import hashlib
import json
import os
import sys
import urllib.request
import uuid
import time
from pathlib import Path

api = os.getenv('DRIVE_API', 'http://localhost:8080/api/v1')
file = Path(sys.argv[1]);
state = Path(sys.argv[2] if len(sys.argv) > 2 else str(file) + '.upload.json')


def call(path, body=None, headers=None):
    req = urllib.request.Request(api + path, data=None if body is None else json.dumps(body).encode(),
                                 headers={'Authorization': os.environ['DRIVE_AUTH'], 'Content-Type': 'application/json',
                                          **(headers or {})})
    with urllib.request.urlopen(req, timeout=900) as r: return json.load(r)


def digest():
    h = hashlib.sha256()
    with file.open('rb') as f:
        while chunk := f.read(1024 * 1024): h.update(chunk)
    return base64.b64encode(h.digest()).decode()


if not state.exists():
    result = call('/uploads',
                  {'name': os.getenv('DRIVE_NAME', file.name), 'expected': 0, 'size': file.stat().st_size, 'sha256': digest()},
                  {'Idempotency-Key': str(uuid.uuid4())})
    state.write_text(json.dumps(result))
session = json.loads(state.read_text());
id = session['id'];
status = call('/uploads/' + id)
if status['state'] == 'COMPLETED': print(json.dumps(status));sys.exit(0)
existing = {p['number']: p for p in status.get('parts', [])}
with file.open('rb') as stream:
    number = 0
    for offset in range(max(1, (session['size'] + session['part_size'] - 1) // session['part_size'])):
        chunk = stream.read(session['part_size'])
        number += 1;
        checksum = base64.b64encode(hashlib.sha256(chunk).digest()).decode()
        if existing.get(number, {}).get('checksum') == checksum: continue
        signed = call('/uploads/' + id + '/parts', {'number': number, 'sha256': checksum})
        headers = {k: v[0] for k, v in signed['headers'].items() if k.lower() != 'host'}
        with urllib.request.urlopen(urllib.request.Request(signed['url'], data=chunk, headers=headers, method='PUT'),
                                    timeout=900) as response:
            assert response.status == 200
call('/uploads/' + id + '/complete', {})
for attempt in range(900):
    status = call('/uploads/' + id)
    if status['state'] == 'COMPLETED':
        print(json.dumps(call('/files/' + status['node']))); break
    if status['state'] != 'COMPLETING': raise RuntimeError('Completion failed: ' + status['state'])
    time.sleep(1)
else: raise TimeoutError('Completion pending; rerun with same session')
