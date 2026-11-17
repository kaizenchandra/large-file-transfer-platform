# Large scanner-enabled objects

ClamAV documents a technical file-size limit of 2 GB: https://github.com/Cisco-Talos/clamav/blob/main/etc/clamd.conf.sample . Increasing StreamMaxLength/MaxFileSize is not a complete 20 GB file-inspection solution.

The default application admission policy is unchanged. An operator may explicitly enable `LARGE_SCAN_REVIEW_MAX_BYTES=20000000000` with `SCAN_MODE=REQUIRED` and a distinct `SCAN_POLICY`. Objects above the ordinary maximum use 32 MiB signature-screening windows with a 64 KiB overlap. Each real ClamAV request is below its configured 100 MiB limit. The final evidence records unique covered bytes, segment count and `segmented-signature-only` scope.

A no-finding result is **REVIEW_REQUIRED**, never CLEAN. Completion retains the object in private quarantine, transfers reserved bytes to forensic accounting, creates no downloadable file version, and exposes no normal signed URL. Segment overlap can detect signatures crossing a boundary within the overlap budget, but cannot preserve arbitrary full-file structure or long-range parser context. A complete coverage count is not full-format inspection or proof of safety. A scanner supporting the original file's full size and format is still required for automatic publication.

An early finding returns BLOCKED with partial byte-coverage evidence. Truncation, excess input and scanner failure fail closed. Worker leases and fencing still govern publication and accounting. This implementation rescans from the start after worker failure; scan-window checkpoints are not durable resumable security decisions.

The measured 20 GB synthetic raw-binary run used 598 scanner segments, covered all 20,000,000,000 bytes and finished QUARANTINED / REVIEW_REQUIRED. It did not complete a malware-cleared 20 GB download pipeline. The browser download workload used a separate, previously verified transfer-only file whose scan verdict was BYPASSED. Keep these results separate.
