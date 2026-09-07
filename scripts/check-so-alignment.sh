#!/usr/bin/env bash
#
# Checks that native libraries support 16 KB memory pages.
#
# Android 15 runs on devices with a 16 KB page size, and a library whose LOAD
# segments are aligned to the older 4 KB simply will not load there. NDK r28 and
# newer align to 16 KB by default, so this should always pass; it exists because
# "should always pass" is exactly the kind of assumption that turns into a crash
# report from a device nobody here owns.
#
# Only 64-bit libraries are checked. The 16 KB page size is an arm64 concern,
# and armeabi-v7a is 32-bit throughout.
#
# Usage:
#   scripts/check-so-alignment.sh <file.aar|file.apk|directory> [...]
#
# Override the NDK with NDK_HOME, or the SDK it lives in with ANDROID_HOME.

set -euo pipefail

REQUIRED_ALIGNMENT=$((16 * 1024))

SDK_ROOT="${ANDROID_HOME:-$HOME/Android/Sdk}"
# Kept in step with ndkVersion in aubio/build.gradle.kts, so the tool that
# checks the libraries comes from the toolchain that built them.
NDK_ROOT="${NDK_HOME:-$SDK_ROOT/ndk/29.0.14206865}"
READELF="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"

if [[ $# -eq 0 ]]; then
    echo "Usage: scripts/check-so-alignment.sh <file.aar|file.apk|directory> [...]" >&2
    exit 2
fi

if [[ ! -x "$READELF" ]]; then
    echo "No llvm-readelf at $READELF" >&2
    echo "Install the NDK with scripts/setup-android-sdk.sh, or set NDK_HOME." >&2
    exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

checked=0
failed=0

check_library() {
    local library="$1"
    local label="$2"

    # 32-bit libraries are not subject to this and would report 0x1000.
    local class
    class="$("$READELF" -h "$library" | awk '/^ *Class:/ { print $2 }')"
    if [[ "$class" != "ELF64" ]]; then
        echo "  skipped $label (32-bit)"
        return
    fi

    checked=$((checked + 1))

    local worst=""
    while read -r alignment; do
        local value=$((alignment))
        if [[ -z "$worst" || $value -lt $worst ]]; then
            worst=$value
        fi
    done < <("$READELF" -lW "$library" | awk '$1 == "LOAD" { print $NF }')

    if [[ -z "$worst" ]]; then
        echo "  FAIL $label: no LOAD segments"
        failed=$((failed + 1))
    elif [[ $worst -lt $REQUIRED_ALIGNMENT ]]; then
        printf '  FAIL %s: aligned to %#x, needs %#x\n' \
            "$label" "$worst" "$REQUIRED_ALIGNMENT"
        failed=$((failed + 1))
    else
        printf '  ok   %s (%#x)\n' "$label" "$worst"
    fi
}

for target in "$@"; do
    echo "$target"

    if [[ -d "$target" ]]; then
        root="$target"
    elif [[ -f "$target" ]]; then
        root="$WORK_DIR/$(basename "$target")"
        mkdir -p "$root"
        # Archives may legitimately hold no libraries at all; that is caught by
        # the count at the end rather than treated as an unzip failure.
        unzip -q -o "$target" '*.so' -d "$root" || true
    else
        echo "  no such file or directory" >&2
        exit 1
    fi

    while IFS= read -r -d '' library; do
        check_library "$library" "${library#"$root"/}"
    done < <(find "$root" -name '*.so' -type f -print0 | sort -z)
done

if [[ $checked -eq 0 ]]; then
    echo "No 64-bit libraries found, so nothing was verified." >&2
    exit 1
fi

if [[ $failed -gt 0 ]]; then
    echo "$failed of $checked libraries are not 16 KB aligned." >&2
    exit 1
fi

echo "$checked libraries are 16 KB aligned."
