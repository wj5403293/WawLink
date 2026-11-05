#!/usr/bin/env bash
# Build without signing credentials; only the final apksigner step reads them.
set +x
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
UNSIGNED=false
if [[ "${1:-}" == --unsigned ]]; then
    UNSIGNED=true
    shift
fi
if (( $# > 1 )); then
    printf 'Usage: %s [--unsigned] [vMAJOR.MINOR.PATCH]\n' "$0" >&2
    exit 2
fi
VERSION="${1:-0.1.5}"
VERSION="${VERSION#v}"
if [[ ! "$VERSION" =~ ^(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$ ]]; then
    printf 'ERROR: expected MAJOR.MINOR.PATCH; minor and patch must be 0..999.\n' >&2
    exit 2
fi
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [[ ! -d "$ANDROID_HOME/platforms/android-35" ]]; then
    printf 'ERROR: install Android SDK Platform 35 and set ANDROID_HOME.\n' >&2
    exit 1
fi
cd -- "$ROOT"
(
    unset WSDX233_KEY_PASSWORD WSDX233_KEYSTORE_BASE64
    ./scripts/fetch-android-binaries.sh
    ./gradlew --no-daemon --no-configuration-cache --max-workers=2 \
        -Pkotlin.compiler.execution.strategy=in-process \
        "-Pwawlink.version=$VERSION" :app-android:assembleRelease
)
if [[ "$UNSIGNED" == true ]]; then
    printf 'Unsigned APK: %s/app-android/build/outputs/apk/release/app-android-release-unsigned.apk\n' "$ROOT"
else
    exec "$ROOT/scripts/sign-release.sh"
fi
