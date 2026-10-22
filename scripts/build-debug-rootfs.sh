#!/usr/bin/env bash
# Build Ubuntu Noble arm64 entirely in user mode. See scripts/USAGE.txt.
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
CACHE="$ROOT/build/downloads"
BUILD="$ROOT/build/debug-rootfs"
ROOTFS="$BUILD/ubuntu-main"
STAGING="$BUILD/ubuntu-main.partial"
OUTPUT="$ROOT/build/outputs/ubuntu-main-debug.tar.zst"
BASE=ubuntu-base-24.04.4-base-arm64.tar.gz
BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/$BASE"
BASE_SHA=04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2
BUN=bun-linux-aarch64.zip
BUN_URL="https://github.com/oven-sh/bun/releases/download/bun-v1.3.14/$BUN"
BUN_SHA=a27ffb63a8310375836e0d6f668ae17fa8d8d18b88c37c821c65331973a19a3b
NODE=node-v22.22.2-linux-arm64.tar.xz
NODE_URL="https://nodejs.org/dist/v22.22.2/$NODE"
NODE_SHA=e9e1930fd321a470e29bb68f30318bf58e3ecb4acb4f1533fb19c58328a091fe
OMP=pi-coding-agent-18.12.6.tgz
OMP_URL=https://registry.npmjs.org/@oh-my-pi/pi-coding-agent/-/pi-coding-agent-18.12.6.tgz
OMP_SHA512=5a8f85709ec0dd163f385b9a7e3e2171d2005dcfd6ecd3316da9f0421dc29b2f2ce7bfa284b38b800bfe1d13b11c192cb04320d0c692730910f71c2a2cf882fe
BUN_LICENSE_URL=https://raw.githubusercontent.com/oven-sh/bun/0d9b296af33f2b851fcbf4df3e9ec89751734ba4/LICENSE.md
BUN_LICENSE_SHA=2c6160ec8fb853f7e8f97d9b249e756c9b0ac44860a68b6bf4f1b0bcbc5c3741

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
trap 'printf "ERROR: rootfs build failed at line %s; previously completed images and archives are preserved.\n" "$LINENO" >&2' ERR
for tool in curl tar sha256sum sha512sum python3 dpkg-deb flock du stat; do
    command -v "$tool" >/dev/null || fail "Required host command missing: $tool (install the host prerequisite; no sudo is used by this script)"
done
mkdir -p "$CACHE" "$BUILD" "$(dirname "$OUTPUT")"
exec 9>"$BUILD/.build.lock"
flock 9

fetch() {
    local url="$1" file="$2" expected="$3" algorithm="${4:-sha256sum}"
    if [[ -f "$file" ]] && printf '%s  %s\n' "$expected" "$file" | "$algorithm" --check --status; then return; fi
    rm -f -- "$file.part"
    curl --fail --location --retry 3 --connect-timeout 30 --output "$file.part" "$url"
    printf '%s  %s\n' "$expected" "$file.part" | "$algorithm" --check --status || fail "Integrity check failed for $url"
    mv -f -- "$file.part" "$file"
}

MACHINE="$(uname -m)"
case "$MACHINE" in
    x86_64) MULTIARCH=x86_64-linux-gnu; NEED_QEMU=1 ;;
    aarch64|arm64) MULTIARCH=aarch64-linux-gnu; NEED_QEMU=0 ;;
    *) fail "Unsupported host architecture $MACHINE; only x86_64 and aarch64 hosts are supported" ;;
esac
# Ubuntu Noble's access syscalls require modern PRoot, even when an older system
# executable exists. Provision the checksum-pinned compatible binary every time.
python3 "$ROOT/scripts/fetch-assets.py" --host-proot
PROOT="$ROOT/build/host-tools/usr/bin/proot"
QEMU="$(command -v qemu-aarch64-static || true)"
ZSTD="$(command -v zstd || true)"
if [[ -z "$PROOT" || -z "$ZSTD" || ( "$NEED_QEMU" == 1 && -z "$QEMU" ) ]]; then
    python3 "$ROOT/scripts/fetch-assets.py" --host-tools
    [[ -n "$QEMU" ]] || QEMU="$ROOT/build/host-tools/usr/bin/qemu-aarch64-static"
    [[ -n "$ZSTD" ]] || ZSTD="$ROOT/build/host-tools/usr/bin/zstd"
fi
export LD_LIBRARY_PATH="$ROOT/build/host-tools/usr/lib/$MULTIARCH${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export QEMU_CPU=max
export PROOT_NO_SECCOMP=1
printf 'Host proot: %s\nHost qemu: %s\nHost zstd: %s\nHost libraries: %s\n' "$PROOT" "${QEMU:-not required on arm64}" "$ZSTD" "$LD_LIBRARY_PATH"
[[ -x "$PROOT" && -x "$ZSTD" ]] || fail 'Host tools could not be provisioned'
if [[ "$NEED_QEMU" == 1 ]]; then [[ -x "$QEMU" ]] || fail 'Missing executable qemu-aarch64-static'; fi

# A completed tree is reused only for the exact builder and pinned inputs. An interrupted
# partial tree is never registered as a valid debug rootfs by the application.
FINGERPRINT="$(cat "$ROOT/scripts/build-debug-rootfs.sh" "$ROOT/scripts/fetch-assets.py" "$ROOT/scripts/assets.lock.json" | sha256sum)"
FINGERPRINT="${FINGERPRINT%% *}"
REUSE=0
if [[ -f "$ROOTFS/.wawlink-ready" ]] && [[ "$(cat "$ROOTFS/.wawlink-ready")" == "$FINGERPRINT" ]]; then REUSE=1; fi
if [[ -e "$ROOTFS" && "$REUSE" == 0 ]]; then
    fail "An existing rootfs has different or incomplete build inputs: $ROOTFS. Preserve it by renaming it before rebuilding (the script never deletes an existing container)."
fi

if [[ "$REUSE" == 0 ]]; then
    fetch "$BASE_URL" "$CACHE/$BASE" "$BASE_SHA"
    # Save the official published checksum list as provenance; the immutable pin above
    # remains authoritative even if the upstream release directory changes later.
    curl --fail --location --retry 3 --output "$CACHE/ubuntu-noble-SHA256SUMS.part" https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS
    grep -F "$BASE_SHA *$BASE" "$CACHE/ubuntu-noble-SHA256SUMS.part" >/dev/null || fail 'Official Ubuntu checksum list no longer contains the pinned rootfs'
    mv -f "$CACHE/ubuntu-noble-SHA256SUMS.part" "$CACHE/ubuntu-noble-SHA256SUMS"
    fetch "$BUN_URL" "$CACHE/$BUN" "$BUN_SHA"
    fetch "$NODE_URL" "$CACHE/$NODE" "$NODE_SHA"
    fetch "$OMP_URL" "$CACHE/$OMP" "$OMP_SHA512" sha512sum
    if [[ -e "$STAGING" ]]; then
        # Only this script's explicitly named, unfinished build directory is disposable.
        [[ ! -L "$STAGING" ]] || fail "Refusing symlink staging directory: $STAGING"
        mkdir -p "$CACHE/ubuntu-apt"
        if [[ -d "$STAGING/var/cache/apt/archives" ]]; then
            find "$STAGING/var/cache/apt/archives" -maxdepth 1 -type f -name '*.deb' -exec cp -n --target-directory="$CACHE/ubuntu-apt" {} +
        fi
        rm -rf -- "$STAGING"
    fi
    mkdir -p "$STAGING"
    tar --extract --gzip --file "$CACHE/$BASE" --directory "$STAGING" --no-same-owner --exclude='./dev/*' --exclude='dev/*'
    mkdir -p "$STAGING"/{dev,proc,sys,tmp,workspace,root/.omp/agent,etc/apt/sources.list.d}
    if [[ -d "$CACHE/ubuntu-apt" ]]; then
        mkdir -p "$STAGING/var/cache/apt/archives"
        cp -a "$CACHE/ubuntu-apt/." "$STAGING/var/cache/apt/archives/"
    fi
    chmod 1777 "$STAGING/tmp"
    rm -f "$STAGING/etc/resolv.conf"
    cp /etc/resolv.conf "$STAGING/etc/resolv.conf"
    cat > "$STAGING/etc/apt/sources.list.d/ubuntu.sources" <<'SOURCES'
Types: deb
URIs: http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports
Suites: noble noble-updates noble-backports
Components: main universe restricted multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports
Suites: noble-security
Components: main universe restricted multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
SOURCES
    : > "$STAGING/etc/apt/sources.list"
    printf '#!/bin/sh\nexit 101\n' > "$STAGING/usr/sbin/policy-rc.d"
    chmod 755 "$STAGING/usr/sbin/policy-rc.d"
    cat > "$STAGING/tmp/wawlink-initialize.sh" <<'GUEST'
#!/bin/bash
set -Eeuo pipefail
export DEBIAN_FRONTEND=noninteractive HOME=/root
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
apt-get update
apt-get install -y --no-install-recommends git curl ca-certificates ripgrep fd-find jq unzip xz-utils zstd libatomic1 libstdc++6 python3 build-essential pkg-config
# HTTPS is available after the authenticated apt bootstrap.
python3 - <<'PY'
from pathlib import Path
p = Path('/etc/apt/sources.list.d/ubuntu.sources')
p.write_text(p.read_text().replace('http://', 'https://'))
PY
mkdir -p /opt/bun /opt/omp /usr/local/share/licenses/bun /usr/local/share/licenses/node
unzip -q /tmp/wawlink-downloads/bun-linux-aarch64.zip -d /opt/bun
install -m755 /opt/bun/bun-linux-aarch64/bun /usr/local/bin/bun
ln -sfn bun /usr/local/bin/bunx
rm -rf /opt/bun
# Node retains npm/corepack, license and complete runtime; no host architecture binaries.
tar -xJf /tmp/wawlink-downloads/node-v22.22.2-linux-arm64.tar.xz -C /usr/local --strip-components=1
cp /usr/local/LICENSE /usr/local/share/licenses/node/LICENSE
mkdir -p /usr/local/share/wawlink
printf 'v22.22.2\n' > /usr/local/share/wawlink/preinstalled-node
# Install the exact integrity-pinned upstream package as the project itself. This
# preserves all OMP native dependencies, lifecycle setup, bundled schemas and assets.
tar -xzf /tmp/wawlink-downloads/pi-coding-agent-18.12.6.tgz -C /opt/omp --strip-components=1
cd /opt/omp
# --production implies frozen-lockfile and writes nothing when the published npm
# tarball has no lock. --omit dev keeps the same runtime graph and saves bun.lock,
# which bun pm trust requires to locate and execute blocked lifecycle scripts.
bun install --omit dev
# Complete dependency lifecycle setup instead of leaving Bun-blocked native addons.
bun pm trust --all
cat > /usr/local/bin/omp <<'WRAPPER'
#!/bin/sh
exec /usr/local/bin/bun /opt/omp/dist/cli.js "$@"
WRAPPER
chmod 755 /usr/local/bin/omp
ln -sfn /usr/bin/fdfind /usr/local/bin/fd
mkdir -p /root/.omp/agent /workspace
printf 'tools:\n  approvalMode: always-ask\n' > /root/.omp/agent/config.yml
printf 'providers: {}\n' > /root/.omp/agent/models.yml
cat > /workspace/hello.js <<'PROJECT'
// A real starter workspace. Run: node hello.js
console.log('你好，WawLink！');
PROJECT
printf '{"name":"wawlink-workspace","private":true,"scripts":{"start":"node hello.js"}}\n' > /workspace/package.json
printf '\nexport PATH=/usr/local/bin:/usr/bin:/bin\nexport LANG=C.UTF-8\ncd /workspace\n' >> /root/.bashrc
# Fail before publishing either a ready tree or an Android archive if any tool is
# unusable under the selected user-mode emulator. The parent runs this script once.
[[ "$(bun --version)" == '1.3.14' ]]
[[ "$(node --version)" == 'v22.22.2' ]]
omp --version | tee /tmp/omp-version.txt
grep -F '18.12.6' /tmp/omp-version.txt >/dev/null
git --version
rg --version
fd --version
jq --version
xz --version
zstd --version
# Keep the dependency graph/lockfile, package licenses and native source licenses.
# Build-only toolchains are removed; the container tool installer can add them later.
apt-get purge -y --auto-remove build-essential pkg-config
apt-get clean
rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/* /root/.cache /root/.bun/install/cache /root/.npm /tmp/omp-version.txt
rm -f /usr/sbin/policy-rc.d
GUEST
    PROOT_ARGS=(-0 -r "$STAGING" -w /root -b /dev -b /proc -b /sys -b /etc/resolv.conf -b "$CACHE:/tmp/wawlink-downloads")
    # Linux host filesystems support real hardlinks. PRoot's link2symlink extension
    # embeds absolute host staging paths in .l2s links, breaking dpkg self-upgrades
    # and making the eventual archive nonportable. It is deliberately not enabled.
    if [[ "$NEED_QEMU" == 1 ]]; then PROOT_ARGS+=(-q "$QEMU"); fi
    "$PROOT" "${PROOT_ARGS[@]}" /usr/bin/env -i HOME=/root TERM=xterm-256color LANG=C.UTF-8 PATH=/usr/local/bin:/usr/bin:/bin /bin/bash /tmp/wawlink-initialize.sh
    rm -f "$STAGING/tmp/wawlink-initialize.sh"
    # The build host's systemd stub is not available on Android; runtime DNS follows the phone.
    printf 'nameserver 223.5.5.5\nnameserver 1.1.1.1\n' > "$STAGING/etc/resolv.conf"
    # Store exact input provenance and license-bearing source manifests in the image.
    mkdir -p "$STAGING/usr/local/share/wawlink"
    cp "$ROOT/scripts/assets.lock.json" "$STAGING/usr/local/share/wawlink/assets.lock.json"
    cp "$CACHE/ubuntu-noble-SHA256SUMS" "$STAGING/usr/local/share/wawlink/ubuntu-SHA256SUMS"
    fetch "$BUN_LICENSE_URL" "$CACHE/bun-LICENSE.md.txt" "$BUN_LICENSE_SHA"
    cp "$CACHE/bun-LICENSE.md.txt" "$STAGING/usr/local/share/licenses/bun/LICENSE.txt"
    printf 'Ubuntu24.04.4 arm64\nBun1.3.14\nNode22.22.2\nOMP18.12.6\n' > "$STAGING/usr/local/share/wawlink/versions.txt"
    printf '%s\n' "$FINGERPRINT" > "$STAGING/.wawlink-ready"
    mv -- "$STAGING" "$ROOTFS"
fi

# Stage archive atomically: a failed compression never replaces a working APK input.
# Numeric root ownership is encoded in tar while all host files remain user-owned.
tar --create --directory "$ROOTFS" --numeric-owner --owner=0 --group=0 --file=- . | "$ZSTD" -T0 -10 --force -o "$OUTPUT.part"
mv -f -- "$OUTPUT.part" "$OUTPUT"
sha256sum "$OUTPUT" > "$OUTPUT.sha256"
EXTRACTED="$(du -sb "$ROOTFS" | cut -f1)"
ARCHIVE="$(stat -c '%s' "$OUTPUT")"
python3 - "$ROOTFS" "$OUTPUT" "$EXTRACTED" "$ARCHIVE" "$FINGERPRINT" <<'REPORT'
import json, pathlib, sys
rootfs, archive, extracted, compressed, fingerprint = sys.argv[1:]
report = {'rootfs':rootfs,'archive':archive,'extractedBytes':int(extracted),'archiveBytes':int(compressed),'buildFingerprint':fingerprint}
p = pathlib.Path(archive + '.sizes.json')
p.write_text(json.dumps(report, indent=2) + '\n')
print(f"Ready rootfs: {rootfs}\nReady archive: {archive}")
print(f"Actual extracted size: {int(extracted):,} bytes ({int(extracted)/1048576:.2f} MiB)")
print(f"Actual archive size: {int(compressed):,} bytes ({int(compressed)/1048576:.2f} MiB)")
print('Full functionality is retained; the original 150–220 MB target is not enforced.')
REPORT
