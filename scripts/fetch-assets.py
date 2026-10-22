#!/usr/bin/env python3
"""Pinned, checksum-verified upstream assets. No target executable is run here."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
LOCK = json.loads((ROOT / "scripts/assets.lock.json").read_text())
CACHE = ROOT / "build/downloads"
RESOURCES = ROOT / "agent-ui/src/commonMain/composeResources"
NATIVE = ROOT / "app-android/src/main/jniLibs/arm64-v8a"
PREFIX = Path("data/data/com.termux/files/usr")


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def download(url, checksum, name=None):
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / (name or url.rsplit("/", 1)[1])
    if path.is_file() and digest(path) == checksum:
        return path
    temporary = path.with_suffix(path.suffix + ".part")
    print(f"Downloading {url}", flush=True)
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "wawlink-assets/1.0"})
        with urllib.request.urlopen(request, timeout=180) as source, temporary.open("wb") as target:
            shutil.copyfileobj(source, target)
        actual = digest(temporary)
        if actual != checksum:
            raise RuntimeError(f"SHA256 mismatch for {url}: expected {checksum}, received {actual}")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)
    return path


def install(source, target, executable=False):
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.is_file() or digest(source) != digest(target):
        temporary = target.with_name(target.name + ".part")
        shutil.copyfile(source, temporary)
        temporary.chmod(0o755 if executable else 0o644)
        temporary.replace(target)
    else:
        target.chmod(0o755 if executable else 0o644)


def unpack(package, target):
    target.mkdir(parents=True, exist_ok=True)
    subprocess.run(["dpkg-deb", "-x", str(package), str(target)], check=True)


def android():
    NATIVE.mkdir(parents=True, exist_ok=True)
    for entry in LOCK["proroot"]:
        install(download(entry["url"], entry["sha256"]), NATIVE / entry["destination"], True)
    vendor = ROOT / "build/vendor/termux"
    license_dir = RESOURCES / "files/native-licenses"
    for entry in LOCK["termux"]:
        package = download("https://packages.termux.dev/apt/termux-main/" + entry["Filename"], entry["SHA256"])
        destination = vendor / entry["Package"]
        unpack(package, destination)
        docs = destination / PREFIX / "share/doc" / entry["Package"]
        if not docs.is_dir():
            raise RuntimeError(f"Upstream package lacks license directory: {docs}")
        for notice in docs.parent.rglob("*"):
            if notice.is_file() and (notice.name == "copyright" or notice.name.startswith("COPYING")):
                suffix = notice.name if notice.parent == docs else str(notice.relative_to(docs.parent)).replace("/", "-")
                install(notice, license_dir / (entry["Package"] + "-" + suffix + ".txt"))
    for entry in LOCK["nativeFiles"]:
        source = vendor / entry["package"] / PREFIX / entry["source"]
        install(source, NATIVE / entry["destination"], True)
    # Preserve the upstream binary-only grant, not an invented open-source license.
    notice = license_dir / "Proroot-upstream-notice.txt"
    license_entry = LOCK["prorootLicense"]
    install(download(license_entry["url"], license_entry["sha256"], "Proroot-upstream-notice.txt"), notice)
    manifest = {"prorootVersion": LOCK["prorootVersion"], "files": []}
    for path in sorted(NATIVE.glob("*.so")):
        manifest["files"].append({"file": path.name, "sha256": digest(path), "bytes": path.stat().st_size})
    (RESOURCES / "files/native-binaries.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Installed {len(manifest['files'])} unmodified Android binaries in {NATIVE}")


def fonts():
    for entry in LOCK["fonts"]:
        install(download(entry["url"], entry["sha256"], entry["destination"].rsplit("/", 1)[1]), RESOURCES / entry["destination"])
    filled = RESOURCES / "font/material_symbols_filled.ttf"
    if not filled.is_file() or digest(filled) != LOCK["filledFont"]["sha256"]:
        try:
            import fontTools
            from fontTools.ttLib import TTFont
            from fontTools.varLib.instancer import instantiateVariableFont
        except ImportError as error:
            raise RuntimeError("To regenerate the committed filled font install fonttools==4.61.1 (python3 -m pip install --user fonttools==4.61.1)") from error
        if fontTools.__version__ != LOCK["filledFont"]["fontTools"]:
            raise RuntimeError("Filled-font regeneration requires fonttools==4.61.1 for reproducible bytes")
        font = TTFont(RESOURCES / "font/material_symbols_rounded.ttf", recalcTimestamp=False)
        instance = instantiateVariableFont(font, {"FILL": 1, "GRAD": 0, "opsz": 24, "wght": 400}, inplace=True)
        instance.save(filled)
        if digest(filled) != LOCK["filledFont"]["sha256"]:
            raise RuntimeError("Generated filled font differs from pinned font; do not silently accept it")
    print(f"Installed official fonts, codepoints and licenses in {RESOURCES}")


def host_proot():
    architecture = {"x86_64": "amd64", "aarch64": "arm64", "arm64": "arm64"}.get(platform.machine())
    if architecture is None:
        raise RuntimeError("Pinned modern PRoot is available only for x86_64 and arm64 hosts")
    target = ROOT / "build/host-tools"
    entry = LOCK["hostProot"][architecture]
    package = download(entry["url"], entry["sha256"], f"proot-{entry['version']}-{architecture}" + (".deb" if architecture == "arm64" else ""))
    if architecture == "amd64":
        install(package, target / "usr/bin/proot", True)
    else:
        unpack(package, target)
        dependency = next(item for item in LOCK["host"][architecture] if item["Package"] == "libtalloc2")
        unpack(download("https://deb.debian.org/debian/" + dependency["Filename"], dependency["SHA256"]), target)
    notice = LOCK["hostProot"]["license"]
    install(download(notice["url"], notice["sha256"], "proot-COPYING"), target / "usr/share/doc/proot/COPYING")
    print(f"Compatible PRoot {entry['version']} installed: {target}/usr/bin/proot")


def host_tools():
    machine = platform.machine()
    architecture = {"x86_64": "amd64", "aarch64": "arm64", "arm64": "arm64"}.get(machine)
    if architecture is None:
        raise RuntimeError(f"No pinned host tools for architecture {machine}; provide proot/qemu-aarch64-static/zstd on PATH")
    target = ROOT / "build/host-tools"
    for entry in LOCK["host"][architecture]:
        # qemu-user-static contains all guest architectures; only retain aarch64.
        package = download("https://deb.debian.org/debian/" + entry["Filename"], entry["SHA256"])
        if entry["Package"] == "qemu-user-static":
            process = subprocess.Popen(["dpkg-deb", "--fsys-tarfile", str(package)], stdout=subprocess.PIPE)
            try:
                with tarfile.open(fileobj=process.stdout, mode="r|") as archive:
                    for member in archive:
                        name = member.name.removeprefix("./")
                        if name == "usr/bin/qemu-aarch64-static" or name.startswith("usr/share/doc/qemu-user-static/"):
                            if member.isfile():
                                destination = target / name
                                destination.parent.mkdir(parents=True, exist_ok=True)
                                with archive.extractfile(member) as source, destination.open("wb") as out:
                                    shutil.copyfileobj(source, out)
                                destination.chmod(member.mode)
            finally:
                process.stdout.close()
            if process.wait() != 0:
                raise RuntimeError("qemu-user-static deb extraction failed")
        else:
            unpack(package, target)
    host_proot()
    qemu = target / "usr/bin/qemu-aarch64-static"
    if not qemu.is_file():
        raise RuntimeError("Pinned qemu package did not contain qemu-aarch64-static")
    print(f"Host tools installed: {target}/usr/bin")
    print(f"Host library search path: {target}/usr/lib/{'x86_64' if architecture == 'amd64' else 'aarch64'}-linux-gnu")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-tools", action="store_true")
    parser.add_argument("--host-proot", action="store_true")
    parser.add_argument("--fonts-only", action="store_true")
    args = parser.parse_args()
    if not shutil.which("dpkg-deb") and not args.fonts_only:
        raise RuntimeError("dpkg-deb is required to unpack verified Debian packages (no root required)")
    if args.host_tools:
        host_tools()
    elif args.host_proot:
        host_proot()
    elif args.fonts_only:
        fonts()
    else:
        android()
        fonts()


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError, urllib.error.URLError) as error:
        print(f"Asset fetch failed: {error}", file=sys.stderr)
        sys.exit(1)
