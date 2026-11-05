#!/bin/sh
# Trusted, bundled installer. OMP/Bun npm archives and the Alpine private glibc
# runtime are pinned to publisher hashes; download selection never changes pins.
set -eu
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
OMP_VERSION=18.12.6
BUN_VERSION=1.3.14
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
progress() { printf '\n==> %s\n' "$*"; }
exists() { [ -e "$1" ] || [ -L "$1" ]; }

repair_dpkg() {
    command -v dpkg >/dev/null 2>&1 || fail 'APT guest is missing dpkg'
    progress 'Repairing any interrupted dpkg transaction'
    DEBIAN_FRONTEND=noninteractive dpkg --configure -a
}

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
ID= VERSION_ID=
[ -r /etc/os-release ] || fail 'Guest is missing /etc/os-release'
. /etc/os-release
case "$ID" in ubuntu|debian|kali|alpine|arch|archarm|fedora) ;; *) fail "Unsupported distribution: $ID" ;; esac
musl=0
for loader in /lib/ld-musl-*.so.1; do if [ -f "$loader" ]; then musl=1; fi; done
[ "$musl" -eq 0 ] || [ "$ID" = alpine ] || fail 'The private musl compatibility runtime is supported on Alpine only'
source_mode=${WAWLINK_INSTALL_SOURCE:-}
if [ -z "$source_mode" ] && [ -f /etc/wawlink-install-source ]; then
    IFS= read -r source_mode < /etc/wawlink-install-source || fail 'Cannot read installation source selection'
fi
if [ -z "$source_mode" ]; then
    # Older/imported containers have no marker. Recognize only the three exact
    # application mirror hosts, never arbitrary substrings or user credentials.
    source_mode=official
    for sources in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources /etc/apk/repositories /etc/pacman.d/mirrorlist /etc/yum.repos.d/*.repo; do
        [ -f "$sources" ] || continue
        if grep -Eq 'https?://(mirrors\.tuna\.tsinghua\.edu\.cn|mirrors\.aliyun\.com|mirrors\.ustc\.edu\.cn)/' "$sources"; then source_mode=domestic; break; fi
    done
fi
case "$source_mode" in
    official) registry=https://registry.npmjs.org; runtime_repository=https://deb.debian.org/debian ;;
    domestic) registry=https://registry.npmmirror.com; runtime_repository=https://mirrors.tuna.tsinghua.edu.cn/debian ;;
    *) fail 'Installation source must be official or domestic' ;;
esac
case "$arch" in
    aarch64) bun_asset=bun-linux-aarch64; triplet=aarch64-linux-gnu; runtime_loader=ld-linux-aarch64.so.1
        bun_sha=5f94ac3d91ecfa260ef11fde7c8711b5cee04f64360e03df9620b1124c7871706e9b0930d1cfc4b0730dc07dc4806a420da67b21854f869086ff104e40713067 ;;
    x64) bun_asset=bun-linux-x64-baseline; triplet=x86_64-linux-gnu; runtime_loader=ld-linux-x86-64.so.2
        bun_sha=abff0474e0b4c9413c14f7a8395abcfcfc3923dfed25a62651f3bfb83500444c7c2149ce247d5127903706316aadef29bc9a22640f40a2f3c9ab916734d8d2c4 ;;
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

progress "Installing $ID $VERSION_ID prerequisites ($source_mode downloads)"
case "$ID" in
    ubuntu|debian|kali)
        export DEBIAN_FRONTEND=noninteractive
        repair_dpkg
        if [ ! -s /etc/ssl/certs/ca-certificates.crt ]; then
            progress 'Bootstrapping CA certificates with signed distribution packages'
            bootstrap_apt_certificates
        fi
        apt-get -o APT::Update::Error-Mode=any update
        apt-get install -y --no-install-recommends ca-certificates curl tar gzip coreutils git ripgrep jq xz-utils zstd libatomic1 libstdc++6 python3 build-essential pkg-config nodejs npm
        ;;
    alpine)
        # OMP's published .node files target glibc. A musl Bun plus gcompat is
        # not an equivalent ABI; keep Alpine musl and isolate glibc below /opt/omp.
        apk add --no-cache ca-certificates curl tar gzip coreutils git ripgrep jq xz zstd libatomic libstdc++ python3 build-base pkgconf nodejs npm binutils patchelf
        ;;
    arch|archarm)
        if [ "$arch" = aarch64 ]; then keyring=archlinuxarm; else keyring=archlinux; fi
        (
            trap 'gpgconf --homedir /etc/pacman.d/gnupg --kill gpg-agent' 0
            pacman-key --init
            pacman-key --populate "$keyring"
        )
        # PRoot/Proroot cannot provide pacman's Landlock/seccomp downloader sandbox.
        # Disable only that process-local sandbox; package signatures stay required.
        pacman -Syu --disable-sandbox --needed --noconfirm "$keyring-keyring" ca-certificates curl tar gzip coreutils git ripgrep jq xz zstd gcc-libs python base-devel nodejs npm
        ;;
    fedora)
        # Fedora 41's original sqlite-libs lacks session symbols required by the
        # updated Node package, but its SONAME dependency still appears satisfied.
        dnf upgrade -y --setopt=install_weak_deps=False sqlite-libs
        dnf install -y --setopt=install_weak_deps=False ca-certificates curl-minimal tar gzip coreutils git ripgrep jq xz zstd libatomic libstdc++ python3 gcc gcc-c++ make pkgconf-pkg-config nodejs npm
        ;;
esac
for tool in curl tar sha256sum sha512sum mktemp install node npm; do
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
        if [ "$musl" -eq 1 ]; then
            for directory in lib usr/lib; do
                key=$(printf '%s' "$directory" | tr / -)
                if [ -f "$stage/changed-$key" ]; then
                    rm -f -- "/$directory/$triplet" || exit 1
                    if exists "$stage/previous-$key"; then
                        mv "$stage/previous-$key" "/$directory/$triplet" || exit 1
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
    curl --fail --location --proto '=https' --proto-redir '=https' --tlsv1.2 --retry 3 --connect-timeout 30 --output "$destination.part" "$url"
    printf '%s  %s\n' "$checksum" "$destination.part" | "$algorithm" --check -
    mv "$destination.part" "$destination"
}
progress "Downloading integrity-pinned Bun $BUN_VERSION ($bun_asset) and OMP $OMP_VERSION from $registry"
fetch "$registry/@oven/$bun_asset/-/$bun_asset-$BUN_VERSION.tgz" "$stage/bun.tgz" "$bun_sha" sha512sum
fetch "$registry/@oh-my-pi/pi-coding-agent/-/pi-coding-agent-$OMP_VERSION.tgz" "$stage/omp.tgz" 5a8f85709ec0dd163f385b9a7e3e2171d2005dcfd6ecd3316da9f0421dc29b2f2ce7bfa284b38b800bfe1d13b11c192cb04320d0c692730910f71c2a2cf882fe sha512sum
mkdir "$stage/bun" "$stage/omp" "$stage/home" "$stage/bin"
tar -xzf "$stage/bun.tgz" -C "$stage/bun" --strip-components=1
install -m755 "$stage/bun/bin/bun" "$bin_stage/bun"
tar -xzf "$stage/omp.tgz" -C "$stage/omp" --strip-components=1
if [ "$musl" -eq 1 ]; then
    progress 'Installing private glibc runtime for OMP (Alpine system libraries unchanged)'
    runtime="$stage/omp/.wawlink-runtime"
    mkdir "$runtime"
    # Debian bookworm publisher Packages indices: libc6 2.36-9+deb12u14 and
    # GCC runtime 12.2.0-14+deb12u1. Extract only; never install .deb into Alpine.
    case "$arch" in
        aarch64)
            packages='pool/main/g/glibc/libc6_2.36-9+deb12u14_arm64.deb 01f4330719fd4f65580e16ea5a0527f372fca750e8f588d26deaf09f2d3b1cf4
pool/main/g/gcc-12/libgcc-s1_12.2.0-14+deb12u1_arm64.deb 576926b283613db80168ddf76380a3bd877602778cf0d226caa7bfbfa71eacf3
pool/main/g/gcc-12/libstdc++6_12.2.0-14+deb12u1_arm64.deb 26e138c677a985775331373828a6c286c551ff397cb735d00e2383cb273d1cb2
pool/main/g/gcc-12/libatomic1_12.2.0-14+deb12u1_arm64.deb 1693aa13ce2b30d061a519fc28b77b9bab8c8e45804ced5969d99821e1bc2159'
            ;;
        x64)
            packages='pool/main/g/glibc/libc6_2.36-9+deb12u14_amd64.deb ba4f88f73dbc3ae9055f3c20f4523bfdbaf1ad13ff95e258924f77d20b4fbedf
pool/main/g/gcc-12/libgcc-s1_12.2.0-14+deb12u1_amd64.deb 3016e62cb4b7cd8038822870601f5ed131befe942774d0f745622cc77d8a88f7
pool/main/g/gcc-12/libstdc++6_12.2.0-14+deb12u1_amd64.deb 5cd3171216d4ab0fc911cfe9c35509bf2dd8f47761c43b7f6a4296701551a24d
pool/main/g/gcc-12/libatomic1_12.2.0-14+deb12u1_amd64.deb fbd4e154a6b444229ea002cc209df099209c0adc09102e5fd21239a3d2b55e2d'
            ;;
    esac
    printf '%s\n' "$packages" | while read -r package checksum; do
        fetch "$runtime_repository/$package" "$stage/runtime.deb" "$checksum" sha256sum
        ar p "$stage/runtime.deb" data.tar.xz > "$stage/runtime.tar.xz"
        tar -xJf "$stage/runtime.tar.xz" -C "$runtime"
    done
    # GNU's multiarch lookup paths are separate from musl's /lib and /usr/lib.
    # Never replace foreign compatibility runtimes or export LD_LIBRARY_PATH to
    # musl children. RPATH rewriting corrupts this upstream Bun ELF on ARM64.
    for directory in lib usr/lib; do
        destination="/$directory/$triplet"
        key=$(printf '%s' "$directory" | tr / -)
        if exists "$destination"; then
            [ -L "$destination" ] && [ "$(readlink "$destination")" = "/opt/omp/.wawlink-runtime/$directory/$triplet" ] || fail "Foreign glibc runtime at $destination; refusing to replace it"
            cp -a -- "$destination" "$stage/previous-$key"
        fi
        : > "$stage/changed-$key"
        ln -s "$runtime/$directory/$triplet" "$destination.wawlink-new"
        mv -Tf "$destination.wawlink-new" "$destination"
    done
    patchelf --set-interpreter "$runtime/lib/$triplet/$runtime_loader" "$bin_stage/bun"
fi
[ "$("$bin_stage/bun" --version)" = "$BUN_VERSION" ] || fail 'Downloaded Bun cannot execute or has an unexpected version'
ln -s "$bin_stage/bun" "$stage/bin/bun"
ln -s "$bin_stage/bun" "$stage/bin/bunx"
progress 'Installing full OMP dependency graph and all native lifecycle scripts'
(
    cd "$stage/omp"
    # Process-local registry selection does not overwrite the user's npm config.
    export HOME="$stage/home" BUN_INSTALL_CACHE_DIR="$stage/cache" PATH="$stage/bin:$PATH"
    export npm_config_registry="$registry" NPM_CONFIG_REGISTRY="$registry" BUN_CONFIG_REGISTRY="$registry"
    "$bin_stage/bun" install --registry "$registry" --backend copyfile --omit dev
    if [ "$source_mode" = domestic ] && [ "$arch" = x64 ]; then
        # The fixed OMP package leaves these postinstalls untrusted until below.
        # Supply the complete publisher-verified assets before executing the
        # unmodified ONNX installer; never set its skip/disable switches.
        "$bin_stage/bun" -e 'if (require("./node_modules/onnxruntime-node/package.json").version !== "1.30.0") throw new Error("Unpinned ONNX native package version")'
        progress 'Downloading full ONNX native assets from domestic NuGet mirror'
        fetch 'https://repo.huaweicloud.com/artifactory/api/nuget/v3/nuget-remote/microsoft.ml.onnxruntime.gpu.linux/1.30.0/microsoft.ml.onnxruntime.gpu.linux.1.30.0.nupkg' "$stage/onnx.nupkg" bc91ba46087f88ea389da28dce7fa75d9a192016ac1733f2b910f9d5293b82ea8e4bf87f6b0720d1884b60da9543ddb6f0645e1d96623f3febb58705e49c6e38 sha512sum
        python3 - "$stage/onnx.nupkg" "$stage/omp/node_modules/onnxruntime-node/bin/napi-v6/linux/x64" <<'ONNX'
import pathlib, shutil, sys, zipfile
target = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(sys.argv[1]) as archive:
    for name in ('libonnxruntime_providers_cuda.so', 'libonnxruntime_providers_shared.so', 'libonnxruntime_providers_tensorrt.so'):
        with archive.open('runtimes/linux-x64/native/' + name) as source, (target / name).open('wb') as destination:
            shutil.copyfileobj(source, destination)
ONNX
    fi
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
if [ "$musl" -eq 1 ]; then
    runtime=/opt/omp/.wawlink-runtime
    patchelf --set-interpreter "$runtime/lib/$triplet/$runtime_loader" "$bin_stage/bun"
fi
if exists /opt/omp; then mv /opt/omp "$stage/previous-omp"; fi
publish_started=1
mv "$stage/omp" /opt/omp
if [ "$musl" -eq 1 ]; then
    for directory in lib usr/lib; do
        destination="/$directory/$triplet"
        ln -s "$runtime/$directory/$triplet" "$destination.wawlink-new"
        mv -Tf "$destination.wawlink-new" "$destination"
    done
fi
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
