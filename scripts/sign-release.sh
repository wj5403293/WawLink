#!/usr/bin/env bash
# Passwords are passed through the process environment, never command arguments.
set +x
set -Eeuo pipefail
umask 077
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
TOOLS="$SDK/build-tools/35.0.0"
OUTPUT="$ROOT/app-android/build/outputs/apk/release"
INPUT="$OUTPUT/app-android-release-unsigned.apk"
KEYSTORE="${WSDX233_KEYSTORE_PATH:-$HOME/.android/wsdx233.keystore}"
CERT_SHA256=d40ca2e0088d61ac708cbc0b2b5772781b098ae1caeb5511a050ac4509a0f471
if [[ ! -f "$INPUT" || ! -x "$TOOLS/apksigner" || ! -x "$TOOLS/zipalign" ]]; then
    printf 'ERROR: unsigned Release APK and Android Build Tools 35.0.0 are required.\n' >&2
    exit 1
fi
if [[ ! -f "$KEYSTORE" ]]; then
    printf 'ERROR: release keystore is missing; set WSDX233_KEYSTORE_PATH.\n' >&2
    exit 1
fi
if [[ -z "${WSDX233_KEY_PASSWORD:-}" && -f "$HOME/.android/.env" ]]; then
    # This is the user's trusted, private shell environment file, not repository input.
    source "$HOME/.android/.env"
fi
if [[ -z "${WSDX233_KEY_PASSWORD:-}" ]]; then
    printf 'ERROR: WSDX233_KEY_PASSWORD is missing; refusing unsigned/debug fallback.\n' >&2
    exit 1
fi
export WSDX233_KEY_PASSWORD
TEMP="$(mktemp -d "$OUTPUT/.signing.XXXXXX")"
trap 'rm -rf -- "$TEMP"; unset WSDX233_KEY_PASSWORD' EXIT
"$TOOLS/zipalign" -c -P 16 4 "$INPUT"
"$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias wsdx233 \
    --ks-pass env:WSDX233_KEY_PASSWORD --key-pass env:WSDX233_KEY_PASSWORD \
    --v4-signing-enabled false --out "$TEMP/app-android-release.apk" "$INPUT"
VERIFICATION="$("$TOOLS/apksigner" verify --verbose --print-certs "$TEMP/app-android-release.apk")"
printf '%s\n' "$VERIFICATION"
if [[ "$VERIFICATION" != *"Signer #1 certificate SHA-256 digest: $CERT_SHA256"* ]]; then
    printf 'ERROR: unexpected release signing certificate.\n' >&2
    exit 1
fi
"$TOOLS/zipalign" -c -P 16 4 "$TEMP/app-android-release.apk"
(
    cd -- "$TEMP"
    sha256sum app-android-release.apk > app-android-release.apk.sha256
)
mv -f -- "$TEMP/app-android-release.apk" "$TEMP/app-android-release.apk.sha256" "$OUTPUT/"
printf 'Signed APK: %s/app-android-release.apk\n' "$OUTPUT"
printf 'Checksum: %s/app-android-release.apk.sha256\n' "$OUTPUT"
