#!/usr/bin/env bash
# Local equivalent of CI's "Build & scan container images" job (ADR-0017 local-first).
# Builds both images and scans them with Trivy (run as a container, nothing installed on the host):
# HIGH/CRITICAL, fixed vulnerabilities only, exit code 1 on findings, same as CI.
#   ./ci/local-image-scan.sh
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 1

TRIVY_IMAGE="${TRIVY_IMAGE:-aquasec/trivy:0.74.0}"
rc=0
echo "Branch: $(git branch --show-current)"
docker build -q -f core/Dockerfile -t iam-core:scan . >/dev/null || { echo "iam-core build failed"; exit 1; }
docker build -q -t iam-web:scan web >/dev/null || { echo "iam-web build failed"; exit 1; }

for img in iam-core:scan iam-web:scan; do
  echo "== Trivy: $img"
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v iam-trivy-cache:/root/.cache/ \
    "$TRIVY_IMAGE" image --quiet --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 "$img" || rc=1
done
[ "$rc" = 0 ] && echo "Result: no fixable HIGH/CRITICAL vulnerabilities" || echo "Result: vulnerabilities found (see tables above)"
exit "$rc"
