#!/usr/bin/env python3
"""Bounded-memory transfer workload. Writes retained files; use a disposable identity.

DRIVE_API and DRIVE_AUTH are required. Output is measured JSON, never a scale claim.
"""
import argparse
import concurrent.futures
import hashlib
import json
import math
import os
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid
from pathlib import Path


def measure(source):
    started = time.perf_counter()
    with tempfile.TemporaryDirectory(prefix='drive-benchmark-') as directory:
        env = dict(os.environ, DRIVE_NAME=f'benchmark-{uuid.uuid4()}-{source.name}')
        result = subprocess.run(
            [sys.executable, str(Path(__file__).resolve().parents[1] / 'docker/transfer.py'),
             str(source), str(Path(directory) / 'session.json')],
            env=env, capture_output=True, text=True, timeout=86400)
        if result.returncode:
            # Do not propagate signed URLs, filenames, credentials or response bodies to metrics.
            raise RuntimeError('upload harness failed')
        node = json.loads(result.stdout)
    uploaded = time.perf_counter()
    request = urllib.request.Request(
        os.environ['DRIVE_API'] + '/files/' + node['id'] + '/download',
        headers={'Authorization': os.environ['DRIVE_AUTH']})
    with urllib.request.urlopen(request, timeout=60) as response:
        download = json.load(response)
    authorized = time.perf_counter()
    digest = hashlib.sha256()
    total = 0
    with urllib.request.urlopen(download['url'], timeout=900) as response:
        while chunk := response.read(128 * 1024):
            digest.update(chunk)
            total += len(chunk)
    import base64
    if total != download['size'] or base64.b64encode(digest.digest()).decode() != download['sha256']:
        raise RuntimeError('download integrity mismatch')
    finished = time.perf_counter()
    # Range behavior is measured separately; empty objects have no satisfiable range.
    range_seconds = None
    if total:
        # The original URL may expire during a multi-GB download. Authorize again.
        with urllib.request.urlopen(request, timeout=60) as response:
            download = json.load(response)
        begin = time.perf_counter()
        with urllib.request.urlopen(urllib.request.Request(download['url'], headers={'Range': 'bytes=0-0'}),
                                    timeout=60) as response:
            if response.status != 206 or len(response.read(2)) != 1:
                raise RuntimeError('range contract failed')
        range_seconds = time.perf_counter() - begin
    return dict(bytes=total, upload_seconds=uploaded - started,
                authorization_seconds=authorized - uploaded, download_seconds=finished - authorized,
                range_seconds=range_seconds)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path, help='immutable sample; retained once per iteration')
    parser.add_argument('--iterations', type=int, default=1)
    parser.add_argument('--workers', type=int, default=1)
    args = parser.parse_args()
    if args.iterations < 1 or args.workers < 1 or not args.source.is_file():
        parser.error('provide a regular file and positive iterations/workers')
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(measure, args.source) for _ in range(args.iterations)]
        results, errors = [], 0
        for future in futures:
            try:
                results.append(future.result())
            except Exception:
                errors += 1
    elapsed = time.perf_counter() - started
    summary = dict(iterations=args.iterations, workers=args.workers, successes=len(results),
                   errors=errors, elapsed_seconds=elapsed,
                   verified_download_bytes_per_second=sum(r['bytes'] for r in results) / elapsed,
                   samples=results)
    for field in ('upload_seconds', 'authorization_seconds', 'download_seconds', 'range_seconds'):
        values = sorted(r[field] for r in results if r[field] is not None)
        if values:
            summary[field] = {f'p{p}': values[min(len(values) - 1, max(0, math.ceil(p / 100 * len(values)) - 1))] for p
                              in (50, 95, 99)}
    print(json.dumps(summary, indent=2))
    return bool(errors)


if __name__ == '__main__':
    sys.exit(main())
