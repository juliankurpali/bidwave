#!/usr/bin/env bash
# Run BidWave entirely on your machine — no AWS account, no keys, no deploy.
set -euo pipefail
cd "$(dirname "$0")/.."
echo "Starting BidWave local server on http://127.0.0.1:8080/"
exec mvn -s .mvn/settings.xml -q exec:java
