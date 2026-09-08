#!/usr/bin/env bash
#
# Measures how well the shipped tempo estimator does.
#
# Builds the host binaries first, so the number always comes from the current
# source rather than from whatever happened to be in the build directory. The
# estimate itself is produced by bpm_probe, which links the same rm_tempo.c the
# phone runs; everything around it is Kotlin in :cadence.
#
# Usage:
#   scripts/bpm-eval.sh                              # synthetic corpus only
#   scripts/bpm-eval.sh --corpus ~/Music/bpm-corpus  # plus real audio
#   scripts/bpm-eval.sh --analyse-seconds 60         # only the middle minute
#
# Every argument is passed through to the CLI; run with --help for the full set.
#
# Results land in aubio/build/bpm-eval, and the committed record of what they
# mean is docs/private/BPM_ACCURACY.md.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$REPO_ROOT"

if ! command -v ffmpeg >/dev/null 2>&1; then
    # Only real audio needs decoding, so this is a warning rather than an exit.
    echo "warning: ffmpeg is not on PATH; only the synthetic corpus can be measured" >&2
fi

echo "Building the host binaries..."
scripts/build-host-aubio.sh > /dev/null

if [[ $# -eq 0 ]]; then
    # Gradle refuses an empty --args rather than treating it as no arguments.
    ./gradlew --quiet --console=plain :cadence:bpmEval
else
    ./gradlew --quiet --console=plain :cadence:bpmEval --args="$*"
fi
