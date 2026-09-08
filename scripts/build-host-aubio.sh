#!/usr/bin/env bash
#
# Builds the tempo library for this machine and runs its tests.
#
# The same CMakeLists that Gradle uses for the NDK, pointed at the host
# compiler instead. That is the point: accuracy gets measured on the code that
# ships rather than on a second implementation of it. It is also a much faster
# way to find out that the source list is missing a file than waiting for
# Gradle to configure two ABIs.
#
# Usage:
#   scripts/build-host-aubio.sh            # configure, build, test
#   scripts/build-host-aubio.sh --clean    # throw the build directory away first
#
# Override the compiler with CC, e.g.
#   CC=clang scripts/build-host-aubio.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="$REPO_ROOT/aubio"
BUILD_DIR="$MODULE_DIR/build/host"

if [[ "${1:-}" == "--clean" ]]; then
    echo "Removing $BUILD_DIR"
    rm -rf "$BUILD_DIR"
elif [[ -n "${1:-}" ]]; then
    echo "Unknown argument: $1" >&2
    echo "Usage: scripts/build-host-aubio.sh [--clean]" >&2
    exit 2
fi

# Deliberately the host's own cmake, not the SDK's. The Android build pins
# cmake 3.31.6; this one runs against whatever the machine has, which is how we
# find out early if the CMakeLists has grown a version dependency.
cmake -S "$MODULE_DIR" -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE=RelWithDebInfo

cmake --build "$BUILD_DIR" --parallel

ctest --test-dir "$BUILD_DIR" --output-on-failure
