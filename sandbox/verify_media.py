#!/usr/bin/env python3
"""Verify a real scanner-enabled MP4 upload and full transcoded artifact.
DRIVE_API and DRIVE_AUTH select a local running deployment. Requires Docker media image.
Usage: python3 sandbox/verify_media.py fixture.mp4 test-results/media-verification.json
"""
import base64, hashlib, json, os, pathlib, subprocess, sys, tempfile, time, urllib.request

root = pathlib.Path(__file__).resolve().parents[1]
source = pathlib.Path(sys.argv[1]).resolve()
report_path = pathlib.Path(sys.argv[2])
api = os.environ['DRIVE_API'].rstrip('/')
auth = os.environ['DRIVE_AUTH']

def call(path):
    with urllib.request.urlopen(urllib.request.Request(api + path, headers={'Authorization': auth}), timeout=30) as response:
        return json.load(response)

def probe(path):
    result = subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--read-only',
        '--memory', '512m', '--cpus', '1', '--pids-limit', '64', '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges', '--user', '65534:65534',
        '--mount', f'type=bind,src={path},dst=/input,readonly',
        os.getenv('SANDBOX_IMAGE', 'harbor-media:local'), 'ffprobe', '-v', 'error',
        '-show_entries', 'format=duration:stream=codec_type,codec_name,width,height',
        '-of', 'json', '/input'], capture_output=True, timeout=30, check=True)
    return json.loads(result.stdout)

started = time.monotonic()
with tempfile.TemporaryDirectory(prefix='harbor-media-verification-') as directory:
    directory = pathlib.Path(directory); directory.chmod(0o755)
    state = directory / 'upload.json'
    subprocess.run([sys.executable, str(root / 'docker/transfer.py'), str(source), str(state)],
                   check=True, stdout=subprocess.DEVNULL, timeout=300)
    upload_id = json.loads(state.read_text())['id']
    upload = call('/uploads/' + upload_id)
    assert upload['state'] == 'COMPLETED' and upload['scan_status'] == 'CLEAN', upload
    node = upload['node']
    deadline = time.monotonic() + 300
    while True:
        jobs = call('/files/' + node + '/processing')
        job = next((j for j in jobs if j['source'] == upload_id), None)
        if job and job['state'] == 'SUCCEEDED': break
        if job and job['state'] in ('FAILED_TERMINAL', 'CANCELLED'): raise AssertionError(job)
        if time.monotonic() > deadline: raise TimeoutError('Media job did not succeed')
        time.sleep(.25)
    variants = call('/files/' + node + '/artifacts')
    assert {'video-preview', 'video-transcoded'} <= {v['variant'] for v in variants}, variants
    credential = call('/files/' + node + '/artifacts/video-transcoded/download')
    with urllib.request.urlopen(credential['url'], timeout=30) as response:
        data = response.read(8 * 1024 * 1024 + 1)
    assert len(data) == credential['size'] <= 8 * 1024 * 1024
    digest = base64.b64encode(hashlib.sha256(data).digest()).decode()
    assert digest == credential['sha256']
    destination = directory / 'transcoded.mp4'; destination.write_bytes(data); destination.chmod(0o644)
    original, output = probe(source), probe(destination)
    duration = float(original['format']['duration']); actual = float(output['format']['duration'])
    assert abs(duration - actual) <= max(.25, duration * .02)
    video = next(s for s in output['streams'] if s['codec_type'] == 'video')
    assert video['codec_name'] == 'h264' and max(video['width'], video['height']) <= 640
    attempts = call('/jobs/' + job['id'] + '/attempts')
    assert attempts[0]['outcome'] == 'SUCCEEDED', attempts
    report = {'outcome': 'passed', 'source_bytes': source.stat().st_size,
              'scan_status': upload['scan_status'], 'source_duration_seconds': duration,
              'output_probe': output, 'output_bytes': len(data), 'output_sha256': digest,
              'artifact_variants': variants, 'processing_attempts': attempts,
              'elapsed_seconds': time.monotonic() - started}
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))
