#!/bin/sh
# Trusted, bundled installer. Pins match scripts/build-debug-rootfs.sh; additional
# Bun libc/desktop pins are from bun-v1.3.14/SHASUMS256.txt (not a moving installer).
set -eu
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
OMP_VERSION=18.12.6
BUN_VERSION=1.3.14
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
progress() { printf '\n==> %s\n' "$*"; }
exists() { [ -e "$1" ] || [ -L "$1" ]; }

# A real native import and RPC round trip, with no user configuration, model call,
# credentials or extensions. The same probe gates both staged and published trees.
write_probe() {
    cat > "$1" <<'PROBE'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
const [tree, scratch, expectedOmp, expectedBun, wrapper] = process.argv.slice(2);
const env = { PATH: '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
  HOME: scratch, TMPDIR: scratch, XDG_CONFIG_HOME: scratch + '/config',
  XDG_DATA_HOME: scratch + '/data', XDG_STATE_HOME: scratch + '/state',
  XDG_CACHE_HOME: scratch + '/cache', PI_CODING_AGENT_DIR: scratch + '/agent',
  LANG: 'C.UTF-8', TERM: 'dumb', PI_NO_PTY: '1', PI_NO_TITLE: '1' };
// PRoot's injected loader is part of the container engine, not user auth/config.
for (const [key, value] of Object.entries(process.env)) {
  if (/^(LD_PRELOAD|LD_LIBRARY_PATH|PROOT_[A-Z_]+|PROROOT_[A-Z_]+)$/.test(key)) env[key] = value;
}
process.env = env;
process.chdir(scratch);
let child;
try {
  if (Bun.version !== expectedBun) throw new Error('Bun version mismatch: ' + Bun.version);
  const pkg = JSON.parse(readFileSync(tree + '/package.json', 'utf8'));
  if (pkg.name !== '@oh-my-pi/pi-coding-agent' || pkg.version !== expectedOmp) throw new Error('OMP package version mismatch');
  const native = await import(pathToFileURL(tree + '/node_modules/@oh-my-pi/pi-natives/native/index.js').href);
  if (native.__piNativesBuildVersion?.() !== expectedOmp) throw new Error('OMP native addon version mismatch');
  // OMP requires a selected model even for get_state. This metadata exists
  // only under the disposable probe HOME: no credentials, discovery or prompt.
  // It is never exposed as a usable user model and its endpoint is not called.
  mkdirSync(env.PI_CODING_AGENT_DIR, { recursive: true });
  writeFileSync(env.PI_CODING_AGENT_DIR + '/models.yml', JSON.stringify({ providers: {
    'wawlink-install-check': { api: 'openai-completions', baseUrl: 'http://127.0.0.1:9/v1', auth: 'none',
      models: [{ id: 'rpc-health', name: 'Disposable RPC installation check', reasoning: false,
        input: ['text'], contextWindow: 4096, maxTokens: 512,
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } }] }
  } }));
  writeFileSync(env.PI_CODING_AGENT_DIR + '/config.yml', JSON.stringify({
    disabledProviders: ['ollama', 'llama.cpp', 'lm-studio', 'apple', 'local', 'web']
  }));
  const version = Bun.spawn(wrapper ? [wrapper, '--version'] : [process.execPath, tree + '/dist/cli.js', '--version'], { cwd: scratch, env, stdout: 'pipe', stderr: 'inherit' });
  const versionTimer = setTimeout(() => version.kill('SIGKILL'), 20000);
  let text;
  let code;
  try { [text, code] = await Promise.all([new Response(version.stdout).text(), version.exited]); }
  finally { clearTimeout(versionTimer); }
  if (code !== 0 || !new RegExp('(^|[^0-9.])' + expectedOmp.replaceAll('.', '\\.') + '([^0-9.]|$)').test(text)) throw new Error('OMP --version failed: ' + text.trim());
  child = Bun.spawn([process.execPath, tree + '/dist/cli.js', '--mode', 'rpc-ui', '--provider', 'wawlink-install-check', '--model', 'wawlink-install-check/rpc-health', '--no-session', '--no-extensions', '--no-skills', '--no-rules', '--no-tools'],
    { cwd: scratch, env, stdin: 'pipe', stdout: 'pipe', stderr: 'inherit' });
  const timer = setTimeout(() => child.kill('SIGKILL'), 45000);
  let ready = false;
  let healthy = false;
  try {
    const reader = child.stdout.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (!healthy) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      if (buffer.length > 1048576) throw new Error('RPC probe output exceeded limit');
      let newline;
      while ((newline = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, newline); buffer = buffer.slice(newline + 1);
        if (!line.trim()) continue;
        let frame;
        try { frame = JSON.parse(line); } catch { continue; }
        if (frame.type === 'ready' && !ready) {
          ready = true;
          child.stdin.write('{"id":"wawlink-install-check","type":"get_state"}\n');
          child.stdin.flush();
        }
        if (frame.type === 'response' && frame.id === 'wawlink-install-check' && frame.command === 'get_state') {
          if (frame.success !== true || !frame.data || typeof frame.data !== 'object') throw new Error('RPC get_state rejected');
          healthy = true;
        }
      }
    }
    if (!ready || !healthy) throw new Error('OMP RPC did not complete ready/get_state within 45 seconds');
  } finally {
    clearTimeout(timer);
    child.kill('SIGKILL');
    await child.exited;
    child = undefined;
  }
  console.log('OMP ' + expectedOmp + ', Bun ' + expectedBun + ', native import and RPC get_state verified');
} catch (error) {
  if (child) { child.kill('SIGKILL'); await child.exited; }
  console.error('Installation health check failed: ' + (error?.stack || error));
  process.exit(1);
}
PROBE
}

if [ "${1:-}" = '--inspect' ]; then
    # Absence is established inside the guest, never inferred from a host path or
    # a container-engine exception. Partial installations are BROKEN, not MISSING.
    if ! exists /opt/omp && ! exists /usr/local/bin/omp && ! command -v omp >/dev/null 2>&1; then
        printf 'WAWLINK_OMP_CHECK\tMISSING\t客体未安装 OMP。\n'
        exit 0
    fi
    if [ ! -x /usr/local/bin/bun ] || [ ! -f /opt/omp/dist/cli.js ] || [ ! -x /usr/local/bin/omp ]; then
        printf 'WAWLINK_OMP_CHECK\tBROKEN\tOMP 或 Bun 安装不完整，保留配置后可修复。\n'
        exit 0
    fi
    probe_dir=$(mktemp -d /tmp/wawlink-omp-check.XXXXXX)
    trap 'rm -rf -- "$probe_dir"' 0
    trap 'exit 130' INT
    trap 'exit 143' TERM HUP
    write_probe "$probe_dir/check.mjs"
    mkdir "$probe_dir/home"
    if /usr/local/bin/bun "$probe_dir/check.mjs" /opt/omp "$probe_dir/home" "$OMP_VERSION" "$BUN_VERSION" /usr/local/bin/omp > "$probe_dir/result" 2>&1; then
        printf 'WAWLINK_OMP_CHECK\tREADY\tOMP 18.12.6 / Bun 1.3.14 原生模块及 RPC 正常。\n'
    else
        printf 'WAWLINK_OMP_CHECK\tBROKEN\tOMP / Bun 版本、原生模块或隔离 RPC 自检失败。\n'
    fi
    exit 0
fi
[ "$#" -eq 0 ] || fail 'Unknown installer argument'
[ "$(id -u)" = 0 ] || fail 'Install/repair requires guest root. Select the container root user; host credentials are never copied.'
[ "$(uname -s)" = Linux ] || fail 'Only Linux guests are supported'
case "$(uname -m)" in
    aarch64|arm64) arch=aarch64 ;;
    x86_64|amd64) arch=x64 ;;
    *) fail "Unsupported guest architecture: $(uname -m) (requires arm64 or x86_64)" ;;
esac
musl=0
for loader in /lib/ld-musl-*.so.1; do if [ -f "$loader" ]; then musl=1; fi; done
case "$arch:$musl" in
    aarch64:0) bun_asset=bun-linux-aarch64; bun_sha=a27ffb63a8310375836e0d6f668ae17fa8d8d18b88c37c821c65331973a19a3b ;;
    aarch64:1) bun_asset=bun-linux-aarch64-musl; bun_sha=b98e0ad3625c5c00d1d5b5ff55605c7adddbfae151861e68ade57b2d3b8703bb ;;
    x64:0) bun_asset=bun-linux-x64-baseline; bun_sha=a063908ae08b7852ca10939bbdc6ceed3ddabce8fb9402dce83d65d73b36e6c7 ;;
    x64:1) bun_asset=bun-linux-x64-musl-baseline; bun_sha=56a7d6806cf155536c0178f0ea5fbd098e684fa509ebdb4fc0a7e19fb65382dc ;;
esac
# Minimal Debian/Ubuntu rootfs images do not contain a TLS trust store. Bootstrap
# only ca-certificates through the same repositories over HTTP, keeping APT's
# archive signatures and package hashes enabled. Never rewrite the user's sources
# or disable TLS verification; all subsequent requests use the original sources.
bootstrap_apt_certificates() (
    ca_stage=$(mktemp -d /tmp/wawlink-omp-ca.XXXXXX)
    trap 'rm -rf -- "$ca_stage"' 0
    trap 'exit 130' INT
    trap 'exit 143' TERM HUP
    mkdir "$ca_stage/sources.list.d"
    : > "$ca_stage/sources.list"
    for source in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
        [ -f "$source" ] || continue
        case "$source" in
            /etc/apt/sources.list) target="$ca_stage/sources.list" ;;
            *) target="$ca_stage/sources.list.d/${source##*/}" ;;
        esac
        sed 's|https://|http://|g' "$source" > "$target"
    done
    set -- -o "Dir::Etc::sourcelist=$ca_stage/sources.list" -o "Dir::Etc::sourceparts=$ca_stage/sources.list.d" -o APT::Update::Error-Mode=any
    apt-get "$@" update
    apt-get "$@" install -y --no-install-recommends ca-certificates
)

progress 'Installing distribution prerequisites (including complete native build/lifecycle support)'
if command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    if [ ! -s /etc/ssl/certs/ca-certificates.crt ]; then
        progress 'Bootstrapping CA certificates with signed distribution packages'
        bootstrap_apt_certificates
    fi
    apt-get -o APT::Update::Error-Mode=any update
    apt-get install -y --no-install-recommends ca-certificates curl unzip tar gzip coreutils git ripgrep jq xz-utils zstd libatomic1 libstdc++6 python3 build-essential pkg-config nodejs npm
elif command -v apk >/dev/null 2>&1; then
    apk add --no-cache ca-certificates curl unzip tar gzip coreutils git ripgrep jq xz zstd libatomic libstdc++ python3 build-base pkgconf nodejs npm gcompat
elif command -v pacman >/dev/null 2>&1; then
    pacman -Syu --needed --noconfirm ca-certificates curl unzip tar gzip coreutils git ripgrep jq xz zstd gcc-libs python base-devel nodejs npm
elif command -v dnf >/dev/null 2>&1; then
    dnf install -y ca-certificates curl unzip tar gzip coreutils git ripgrep jq xz zstd libatomic libstdc++ python3 gcc gcc-c++ make pkgconf-pkg-config nodejs npm
else
    fail 'Unsupported distribution: requires apt-get, apk, pacman or dnf'
fi
for tool in curl unzip tar sha256sum sha512sum mktemp install node npm; do
    command -v "$tool" >/dev/null 2>&1 || fail "Missing required guest command: $tool"
done
mkdir -p /opt /usr/local/bin
[ ! -L /opt/omp ] || fail '/opt/omp is a symlink; refusing to replace an unknown installation target'
[ ! -e /opt/omp ] || [ -d /opt/omp ] || fail '/opt/omp exists but is not a directory'
# Each replacement and its backup live on the destination filesystem. Never
# remove rootfs, .omp, workspaces, authentication, configuration or session data.
if ! mkdir /opt/.wawlink-omp-install.lock; then fail 'Another OMP repair is active, or an interrupted repair left /opt/.wawlink-omp-install.lock. Inspect its terminal before removing that lock.'; fi
trap 'rmdir /opt/.wawlink-omp-install.lock' 0
stage=$(mktemp -d /opt/.wawlink-omp.XXXXXX)
bin_stage=''
publish_started=0
committed=0
cleanup() {
    status=$?
    trap - 0 INT TERM HUP
    if [ "$committed" -eq 0 ]; then
        printf '\nERROR: OMP installation did not complete (exit %s). Restoring previous installation.\n' "$status" >&2
        if [ "$publish_started" -eq 1 ] && [ ! -d "$stage/omp" ] && exists /opt/omp; then
            if ! mv /opt/omp "$stage/rejected-omp"; then printf 'ERROR: Cannot preserve failed candidate; recovery files: %s\n' "$stage" >&2; exit 1; fi
        fi
        if exists "$stage/previous-omp"; then
            if ! mv "$stage/previous-omp" /opt/omp; then printf 'ERROR: Restore failed; previous OMP retained at %s/previous-omp\n' "$stage" >&2; exit 1; fi
        fi
        if [ -n "$bin_stage" ]; then
            for name in bun bunx omp; do
                if [ -f "$bin_stage/changed-$name" ]; then
                    if ! rm -f -- "/usr/local/bin/$name"; then printf 'ERROR: Cannot restore %s; backup at %s\n' "$name" "$bin_stage" >&2; exit 1; fi
                    if exists "$bin_stage/previous-$name"; then
                        if ! mv "$bin_stage/previous-$name" "/usr/local/bin/$name"; then printf 'ERROR: Cannot restore %s; backup at %s\n' "$name" "$bin_stage" >&2; exit 1; fi
                    fi
                fi
            done
        fi
        [ "$status" -ne 0 ] || status=1
    fi
    rm -rf -- "$stage"
    if [ -n "$bin_stage" ]; then rm -rf -- "$bin_stage"; fi
    rmdir /opt/.wawlink-omp-install.lock
    if [ "$committed" -eq 1 ] && [ "$status" -eq 0 ]; then
        progress 'OMP 18.12.6 / Bun 1.3.14 installed successfully; native import and RPC verified. Existing configuration, authentication, sessions and rootfs preserved.'
    fi
    exit "$status"
}
trap cleanup 0
trap 'exit 130' INT
trap 'exit 143' TERM HUP
bin_stage=$(mktemp -d /usr/local/bin/.wawlink-omp.XXXXXX)
fetch() {
    url=$1 destination=$2 checksum=$3 algorithm=$4
    curl --fail --location --proto '=https' --tlsv1.2 --retry 3 --connect-timeout 30 --output "$destination.part" "$url"
    printf '%s  %s\n' "$checksum" "$destination.part" | "$algorithm" --check -
    mv "$destination.part" "$destination"
}
progress "Downloading integrity-pinned Bun $BUN_VERSION ($bun_asset) and OMP $OMP_VERSION"
fetch "https://github.com/oven-sh/bun/releases/download/bun-v$BUN_VERSION/$bun_asset.zip" "$stage/bun.zip" "$bun_sha" sha256sum
fetch "https://registry.npmjs.org/@oh-my-pi/pi-coding-agent/-/pi-coding-agent-$OMP_VERSION.tgz" "$stage/omp.tgz" 5a8f85709ec0dd163f385b9a7e3e2171d2005dcfd6ecd3316da9f0421dc29b2f2ce7bfa284b38b800bfe1d13b11c192cb04320d0c692730910f71c2a2cf882fe sha512sum
unzip -q "$stage/bun.zip" -d "$stage/bun"
install -m755 "$stage/bun/$bun_asset/bun" "$bin_stage/bun"
[ "$("$bin_stage/bun" --version)" = "$BUN_VERSION" ] || fail 'Downloaded Bun cannot execute or has an unexpected version'
mkdir "$stage/omp" "$stage/home" "$stage/bin"
ln -s "$bin_stage/bun" "$stage/bin/bun"
ln -s "$bin_stage/bun" "$stage/bin/bunx"
tar -xzf "$stage/omp.tgz" -C "$stage/omp" --strip-components=1
progress 'Installing full OMP dependency graph and all native lifecycle scripts'
(
    cd "$stage/omp"
    # An isolated HOME preserves user auth/config; no --ignore-scripts or omitted
    # optional/native dependencies. Keep the upstream source layout and bun.lock.
    export HOME="$stage/home" BUN_INSTALL_CACHE_DIR="$stage/cache" PATH="$stage/bin:$PATH"
    # Copy files rather than PRoot link2symlink hardlinks into the disposable cache.
    "$bin_stage/bun" install --backend copyfile --omit dev
    "$bin_stage/bun" pm trust --all
)
write_probe "$stage/check.mjs"
progress 'Checking staged versions, native import and real RPC ready/get_state'
"$bin_stage/bun" "$stage/check.mjs" "$stage/omp" "$stage/home" "$OMP_VERSION" "$BUN_VERSION"
cat > "$bin_stage/omp" <<'WRAPPER'
#!/bin/sh
exec /usr/local/bin/bun /opt/omp/dist/cli.js "$@"
WRAPPER
chmod 755 "$bin_stage/omp"
ln -s bun "$bin_stage/bunx"
progress 'Switching validated installation (previous files retained until final check)'
if exists /opt/omp; then mv /opt/omp "$stage/previous-omp"; fi
publish_started=1
mv "$stage/omp" /opt/omp
for name in bun bunx omp; do
    # Set the rollback marker before changing the existing destination.
    # A directory is never an executable and cannot be safely replaced here.
    [ ! -d "/usr/local/bin/$name" ] || fail "/usr/local/bin/$name is a directory"
    if exists "/usr/local/bin/$name"; then
        cp -a -- "/usr/local/bin/$name" "$bin_stage/previous-$name"
    fi
    : > "$bin_stage/changed-$name"
    mv "$bin_stage/$name" "/usr/local/bin/$name"
done
progress 'Verifying published /opt/omp and /usr/local/bin installation'
/usr/local/bin/bun "$stage/check.mjs" /opt/omp "$stage/home" "$OMP_VERSION" "$BUN_VERSION" /usr/local/bin/omp
committed=1
