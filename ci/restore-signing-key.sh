#!/usr/bin/env bash
set -euo pipefail
mkdir -p ci
curl -fsSL https://raw.githubusercontent.com/skt-pj/2048TD/main/ci/2048td-release.jks.b64 \
  | base64 --decode > ci/skt-common-signing.jks
