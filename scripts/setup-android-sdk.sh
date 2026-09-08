#!/usr/bin/env bash
#
# Bootstraps the Android SDK needed to build RunningMusic on a Linux host.
#
# Usage:
#   scripts/setup-android-sdk.sh                 # toolchain only
#   scripts/setup-android-sdk.sh --with-emulator # also install the emulator and system images
#
# Override the install location with ANDROID_HOME, e.g.
#   ANDROID_HOME=/opt/android-sdk scripts/setup-android-sdk.sh

set -euo pipefail

SDK_ROOT="${ANDROID_HOME:-$HOME/Android/Sdk}"

# cmdline-tools 23.0, the current stable release.
CMDLINE_TOOLS_ZIP="commandlinetools-linux-16111833_latest.zip"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"
CMDLINE_TOOLS_SHA1="e025545c62a8e64c7559119566a569fb1dec5f60"

# compileSdk 37 resolves to platforms;android-37.0. Platforms have been
# minor-versioned since Android 16, so there is no bare "android-37" package.
PACKAGES=(
    "platform-tools"
    "platforms;android-37.0"
    "build-tools;37.0.0"
    # r28 and newer default to 16 KB-aligned native libraries, which Android 15+
    # requires. Drop to 28.2.13676358 (AGP 9's own default) if AGP rejects this.
    "ndk;29.0.14206865"
    # Pinned so the native build uses the SDK's CMake rather than whatever the
    # host has on PATH.
    "cmake;3.31.6"
)

EMULATOR_PACKAGES=(
    "emulator"
    # minSdk, never exercised by the physical test device.
    "system-images;android-28;google_apis;x86_64"
    # targetSdk, and ps16k exercises the 16 KB page size the NDK work must support.
    "system-images;android-37.0;google_apis_ps16k;x86_64"
)

with_emulator=false
for arg in "$@"; do
    case "$arg" in
        --with-emulator) with_emulator=true ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

if $with_emulator; then
    PACKAGES+=("${EMULATOR_PACKAGES[@]}")
fi

command -v java >/dev/null || { echo "java not found on PATH" >&2; exit 1; }

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

if [[ ! -x "$SDKMANAGER" ]]; then
    echo "==> Installing cmdline-tools into $SDK_ROOT"
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT

    curl -fL --progress-bar -o "$tmp/$CMDLINE_TOOLS_ZIP" "$CMDLINE_TOOLS_URL"
    echo "$CMDLINE_TOOLS_SHA1  $tmp/$CMDLINE_TOOLS_ZIP" | sha1sum -c -

    unzip -q "$tmp/$CMDLINE_TOOLS_ZIP" -d "$tmp"
    # The zip unpacks to cmdline-tools/, but sdkmanager requires it to live at
    # cmdline-tools/latest/ to resolve SDK_ROOT correctly.
    mkdir -p "$SDK_ROOT/cmdline-tools"
    mv "$tmp/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
else
    echo "==> cmdline-tools already present at $SDK_ROOT"
fi

echo "==> Accepting SDK licenses"
# pipefail off for this one pipeline: sdkmanager closes stdin once it has read
# every license, so `yes` always dies of SIGPIPE and would abort the script.
set +o pipefail
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null
set -o pipefail

echo "==> Installing packages"
for pkg in "${PACKAGES[@]}"; do
    echo "    $pkg"
done
"$SDKMANAGER" --sdk_root="$SDK_ROOT" "${PACKAGES[@]}"

cat <<EOF

Done. Add to your shell profile if not already present:

    export ANDROID_HOME="$SDK_ROOT"
    export PATH="\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH"

platform-tools must precede /usr/lib/android-sdk on PATH; two adb versions will
repeatedly kill each other's servers.
EOF
