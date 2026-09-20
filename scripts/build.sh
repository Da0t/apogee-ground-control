#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -B clean
npm --prefix web ci
npm --prefix web run build
mkdir -p target/classes/static
cp -R web/dist/. target/classes/static/
./mvnw -B package -DskipTests
