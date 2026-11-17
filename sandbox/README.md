# Local sandbox broker

Build `docker build -t harbor-media:local sandbox`, set a strong `SANDBOX_TOKEN`, then run `python3 sandbox/broker.py`. Configure `MEDIA_SANDBOX_URL=http://localhost:19091` and `MEDIA_SANDBOX_TOKEN` on the backend. The broker binds loopback only; do not expose it publicly. It is a trusted Docker control-plane component. Neither the backend nor the parser container receives a Docker socket.

Each PDF/video request has a 64 MiB admission limit. Two jobs may run concurrently. A fresh container runs as UID 65534 with no network, no capabilities, no new privileges, a read-only root filesystem, 1 CPU, 512 MiB memory/swap ceiling, 64 PIDs, 64 MiB temporary scratch and 16 MiB output tmpfs. Each parser/encoder command is bounded to 45 seconds, duration probes to 10 seconds and final decode validation to 15 seconds; output files are limited to 8 MiB. Only the input file is mounted read-only. Artifact extraction uses an explicit size-capped command, not a host-writable parser output mount. PDF renders the first page; video extracts the first frame. `/transcode` accepts MP4 inputs through 120 seconds and generates a complete H.264/AAC MP4 inside the same limits, scaled within 640×640. It probes the duration before and after conversion and decodes the output before returning it. Over-budget or failed conversions are rejected; the encoder does not silently truncate the source.

The broker requires access to the trusted Docker daemon and host temporary paths. This is a local deployment reference, not a Kubernetes multi-tenant sandbox service. Production should use isolated worker nodes or a dedicated runtime such as gVisor/Kata, authenticated internal transport and an immutable reviewed image digest. Rebuilds currently install distribution packages; record the actual image ID and package versions for every measured run.

The development recovery harness is disabled unless HARBOR_RECOVERY_LAB=1. Run it only on localhost. Browser tests use real Chromium OPFS handles; native file-picker permission prompts and OS crash recovery remain distinct tests.

## Reproduce full video verification

With a running REQUIRED-scanner API and configured broker:

```sh
export DRIVE_API=http://localhost:28084/api/v1
export DRIVE_AUTH='Basic <base64(alice:local-password)>'
python3 sandbox/verify_media.py /absolute/path/fixture.mp4 test-results/media-verification.json
SANDBOX_TOKEN="$MEDIA_SANDBOX_TOKEN" python3 sandbox/test_broker.py
```

The harness requires CLEAN scanner status, a successful durable job/attempt, both video variants, a matching downloaded SHA-256, H.264 output within 640×640, and preservation of complete duration. It uses ffprobe in isolated containers and does not write signed download URLs or credentials to the report. The measured fixture was a synthetic two-second video; long/complex production media performance is unverified.
