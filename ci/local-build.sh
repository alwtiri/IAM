#!/usr/bin/env bash
# Local backend build exactly like CI's "Backend build" job, without installing Java (ADR-0017 local-first).
# Runs ./gradlew build inside the pinned JDK image. Testcontainers-based tests (PostgresMigrationIT) reach the
# host Docker daemon through the mounted socket; --network host makes the test containers' ports reachable.
#   ./ci/local-build.sh                 # full build + all tests
#   ./ci/local-build.sh :core:test      # any Gradle arguments
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

JDK_IMAGE="eclipse-temurin:21-jdk-noble@sha256:4d271cd5e0624598cf563342f47281b09cb364bc13acbbd7251f49f83470018d"
[ $# -gt 0 ] || set -- build

echo "Branch: $(git branch --show-current)   Gradle: $*"
exec docker run --rm \
  --network host \
  -v "$PWD":/w -w /w \
  -v iam-gradle-cache:/root/.gradle \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  "$JDK_IMAGE" ./gradlew --no-daemon "$@"
