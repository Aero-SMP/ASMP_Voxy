"""Analyze stock allocation recordings and full-loop Minecraft frame archives."""

import argparse
import collections
import csv
import datetime
import gzip
import io
import json
import math
import zipfile
from pathlib import Path


def events(path):
    decoder = json.JSONDecoder()
    open_file = gzip.open if path.suffix == ".gz" else open
    with open_file(path, "rt") as file:
        buffer = file.read(65536)
        marker = '"events": ['
        buffer = buffer[buffer.index(marker) + len(marker) :]
        while True:
            buffer = buffer.lstrip(" \n\r\t,")
            if buffer.startswith("]"):
                return
            try:
                event, offset = decoder.raw_decode(buffer)
            except json.JSONDecodeError:
                added = file.read(65536)
                if not added:
                    raise
                buffer += added
                continue
            buffer = buffer[offset:]
            yield event


def frame_metrics(args):
    receipt = json.loads(args.frame_receipt.read_text())
    if receipt["sha256"] != args.sha256:
        raise ValueError("Frame receipt artifact differs from the requested artifact")
    archives = sorted(args.events.glob("*.zip"))
    if len(archives) != len(receipt["reports"]) or not archives:
        raise ValueError("Downloaded archives do not match the capture receipt")
    combined, captures = [], []

    def summary(values):
        if not values or any(
            not math.isfinite(value) or value <= 0 for value in values
        ):
            raise ValueError("Invalid full-loop frame times")
        seconds = sum(values) / 1e9
        return {
            "frames": len(values),
            "seconds": seconds,
            "fps": len(values) / seconds,
            "p99_5_ms": sorted(values)[math.ceil(len(values) * 0.995) - 1] / 1e6,
        }

    for archive in archives:
        with zipfile.ZipFile(archive) as file:
            with file.open("client/metrics/ticking.csv") as raw:
                rows = csv.DictReader(io.TextIOWrapper(raw))
                values = [float(row["ticktime"]) for row in rows]
        combined.extend(values)
        captures.append({"archive": archive.name, **summary(values)})
    result = {
        "artifactSha256": args.sha256,
        "minecraftPid": receipt["minecraftPid"],
        "startUtc": receipt["startUtc"],
        "foreground": receipt["foreground"],
        "method": (
            "Discontinuous stock ten-second F3+L client metrics captures. "
            "ticktime is full client-loop nanoseconds; FPS is frames divided by "
            "summed durations; p99.5 uses nearest rank. Stock profiling overhead "
            "remains. JFR is recorded separately."
        ),
        **summary(combined),
        "captures": captures,
    }
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("events", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("sha256")
    parser.add_argument("--seconds", type=float, default=60)
    parser.add_argument("--stack-depth", type=int, default=5)
    parser.add_argument("--frames", action="store_true")
    parser.add_argument("--frame-receipt", type=Path)
    args = parser.parse_args()
    if args.frames:
        if not args.frame_receipt:
            parser.error("--frames requires --frame-receipt")
        return frame_metrics(args)
    seen = set()
    sites = collections.Counter()
    classes = collections.Counter()
    counters = {}
    excluded = total = voxy = 0
    for event in events(args.events):
        value = event["values"]
        if event["type"] == "jdk.ObjectAllocationSample":
            thread = value["eventThread"]["javaThreadId"]
            weight = value["weight"]
            if thread not in seen:
                seen.add(thread)
                excluded += weight
                continue
            total += weight
            frames = (value.get("stackTrace") or {}).get("frames", [])
            methods = [
                frame["method"]["type"]["name"] + "." + frame["method"]["name"]
                for frame in frames
            ]
            owned = next(
                (
                    method
                    for method in methods
                    if method.startswith(("com/aerosmp/voxy/", "com.aerosmp.voxy."))
                ),
                None,
            )
            if owned:
                voxy += weight
                sites[owned] += weight
                classes[value["objectClass"]["name"]] += weight
        elif event["type"] == "jdk.ThreadAllocationStatistics":
            thread = value["thread"]
            thread_id = thread["javaThreadId"]
            timestamp = datetime.datetime.fromisoformat(value["startTime"])
            allocated = value["allocated"]
            if thread_id not in counters:
                counters[thread_id] = [
                    thread["javaName"],
                    timestamp,
                    allocated,
                    timestamp,
                    allocated,
                ]
            else:
                counters[thread_id][3:] = timestamp, allocated
    exact = []
    delta = 0
    for name, first, before, last, after in counters.values():
        seconds = (last - first).total_seconds()
        delta += after - before
        if seconds and ("voxy" in name.lower() or name == "Render thread"):
            exact.append(
                {
                    "thread": name,
                    "seconds": seconds,
                    "allocatedBytes": after - before,
                    "MiBPerSecond": (after - before) / seconds / 1048576,
                }
            )
    result = {
        "artifactSha256": args.sha256,
        "seconds": args.seconds,
        "exportStackDepth": args.stack_depth,
        "method": "Only JFR ObjectAllocationSample and 1s ThreadAllocationStatistics; no simultaneous F3+L. First allocation sample per thread omitted because it includes pre-recording accumulated bytes. Weighted values estimate stack attribution; exact counters are whole-thread totals.",
        "firstThreadWeightsExcludedBytes": excluded,
        "totalWeightedMiBPerSecond": total / args.seconds / 1048576,
        "voxyWeightedMiBPerSecond": voxy / args.seconds / 1048576,
        "voxyWeightedMBPerSecond": voxy / args.seconds / 1000000,
        "voxyWeightedShare": voxy / total if total else 0,
        "exactVoxyAndRenderThreads": exact,
        "allExactCounterDeltaBytes": delta,
        "voxyAllocationSites": dict(sites.most_common(15)),
        "voxyAllocationClasses": dict(classes.most_common(12)),
    }
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(
        json.dumps(
            {
                key: value
                for key, value in result.items()
                if key not in ("voxyAllocationClasses", "voxyAllocationSites")
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
