#!/usr/bin/env python3
"""Publish the fixed Voxy artifact, then atomically announce its verified SHA-256."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import zipfile


def publish(jar, side, directory):
    if side not in ("client", "server") or jar.name != f"voxy-{side}-debug.jar":
        raise ValueError("wrong artifact filename or side")
    with zipfile.ZipFile(jar) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode()
        attrs = dict(
            line.split(": ", 1) for line in manifest.splitlines() if ": " in line
        )
        if attrs["Voxy-Update-Side"] != side:
            raise ValueError("wrong artifact side")
        identity = "voxy" if side == "client" else "voxy_server"
        if (
            f'modId="{identity}"'
            not in archive.read("META-INF/neoforge.mods.toml").decode()
        ):
            raise ValueError("wrong mod identity")
        if "com/aerosmp/voxy/update/AutoUpdater.class" not in archive.namelist():
            raise ValueError("updater missing")
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    feed = directory / side
    feed.mkdir(parents=True, exist_ok=True)
    latest = feed / "latest.properties"
    target = feed / jar.name
    staged = feed / ("." + jar.name + ".part")
    shutil.copyfile(jar, staged)
    with staged.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != digest:
            raise ValueError("staged checksum mismatch")
        os.fsync(stream.fileno())
    os.replace(staged, target)
    announcement = feed / ".latest.properties.part"
    with announcement.open("w") as stream:
        stream.write(f"file={jar.name}\nsha256={digest}\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(announcement, latest)
    return latest


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--side", required=True, choices=["client", "server"])
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument(
        "--directory",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "releases",
    )
    args = parser.parse_args()
    print(publish(args.jar, args.side, args.directory))
