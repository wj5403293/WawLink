#!/usr/bin/env bash
# Fetch pinned, unmodified upstream Android arm64 executables/dependencies and UI fonts.
# No root, Docker, target executable invocation or native rebuilding is involved.
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
command -v python3 >/dev/null || { printf 'ERROR: python3 is required\n' >&2; exit 1; }
command -v flock >/dev/null || { printf 'ERROR: flock is required\n' >&2; exit 1; }
mkdir -p "$ROOT/build"
exec 9>"$ROOT/build/fetch-assets.lock"
flock 9
exec python3 "$ROOT/scripts/fetch-assets.py" "$@"
