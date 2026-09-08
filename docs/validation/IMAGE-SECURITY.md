# Development database image security

Scan date: 2026-09-08. Scope: the local linux/arm64 image only.

## Pinned artifact and findings

PostgreSQL 17.11 Alpine, manifest digest `sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`.

Trivy 0.74.0 reported **1 critical, 30 high, 28 medium, 14 low and 1 unknown** package-vulnerability entries (74 total, not 74 distinct CVEs). See the [sanitized machine-readable report](postgres-image-scan.json) for package versions, fixed versions, references and scanner warnings.

The critical finding is CVE-2025-68121 in the Go standard library embedded in gosu. This scan does not determine exploitability in our application. No findings have been suppressed. Trivy also warned that the Alpine version was absent from its EOL list and that some severities came from vendor sources.

A Debian-based PostgreSQL 17.11 candidate was also scanned and had more reported entries. The Alpine choice reduces this scan's package finding count, but does not establish that the image is safe.

## Current decision

Allow only isolated local synthetic development/testing. Both API profiles are loopback-only; Compose binds PostgreSQL to loopback. There is no public deployment, real data, production credential or clean-image claim.

Before deployment, update/rebuild or replace the image, re-scan each target platform, assess exposure, document remediation and resolve deployment-blocking findings. The amd64 image used by CI has not been scanned by this local record. Functional CI is not a security certification.

## Reproduce

Using Trivy 0.74.0 and a running Docker daemon:

```sh
trivy image --scanners vuln --format json \
  postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73
```

The scanner database changes over time, so a fresh scan may report different results. The report is a point-in-time observation, not a permanent allowlist. Scan application dependencies and runtime images separately before deployment.
