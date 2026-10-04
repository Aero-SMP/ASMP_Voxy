"""Read an existing live run; never launch, restart, or alter its clients."""

import argparse
import collections
import json
from pathlib import Path
import subprocess
import time


def read_events(directory, cursor):
    with (directory / "clients.jsonl").open() as events:
        identity = str((directory / "clients.jsonl").stat().st_ino)
        if cursor.get("inode") != identity or cursor.get("offset", 0) > events.seek(
            0, 2
        ):
            cursor = {"inode": identity, "offset": 0, "maxActive": 0, "peers": {}}
        events.seek(cursor.get("offset", 0))
        while True:
            offset = events.tell()
            line = events.readline()
            if not line or not line.endswith("\n"):
                events.seek(offset)
                break
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            cursor["lastEvent"] = {
                key: value for key, value in event.items() if key != "peer_changes"
            }
            cursor["maxActive"] = max(
                cursor.get("maxActive", 0), event.get("active", 0)
            )
            for peer in event.get("peer_changes", []):
                cursor.setdefault("peers", {})[str(peer["id"])] = peer
        cursor["offset"] = events.tell()
    return cursor


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    parser.add_argument("--native-pid", required=True, type=int)
    args = parser.parse_args()
    directory = args.run
    metadata = json.loads((directory / "run.json").read_text())
    cursor_file = directory / "pressure-observer-state.json"
    cursor = json.loads(cursor_file.read_text()) if cursor_file.exists() else {}
    cursor = read_events(directory, cursor)
    cursor_file.write_text(json.dumps(cursor) + "\n")
    resources = json.loads(
        subprocess.check_output(
            ["tail", "-n", "1", str(directory / "resources.jsonl")], text=True
        )
    )
    native = resources["processes"][str(args.native_pid)]
    cgroup = native.get("cgroup", {})
    counts = [
        len(list(peer.glob("*.vxs")))
        for peer in (directory / "cache").iterdir()
        if peer.is_dir()
    ]
    output = {
        "epoch": time.time(),
        "seconds": time.time() - metadata["started_epoch"],
        "lastEvent": cursor.get("lastEvent"),
        "maxActive": cursor["maxActive"],
        "phases": dict(
            collections.Counter(peer["phase"] for peer in cursor["peers"].values())
        ),
        "frames": sum(counts),
        "clientsWithCache": sum(count > 0 for count in counts),
        "full597": sum(count >= 597 for count in counts),
        "nativeRss": next(
            (
                line.strip()
                for line in native.get("status", "").splitlines()
                if line.startswith("VmRSS:")
            ),
            None,
        ),
        "nativeCgroup": {
            key: cgroup.get(key, "").strip()
            for key in (
                "memory.current",
                "memory.peak",
                "memory.max",
                "memory.swap.max",
                "memory.events",
            )
        },
        "observerError": native.get("error"),
        "savedLogBytes": (directory / "saved-changes.jsonl").stat().st_size,
        "terminalMetadata": {
            key: metadata[key]
            for key in ("driver_exit", "failure", "persisted_rate_during_pressure")
            if key in metadata
        },
    }
    (directory / "latest-observation.json").write_text(
        json.dumps(output, indent=2) + "\n"
    )
    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main()
