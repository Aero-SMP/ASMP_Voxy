#!/usr/bin/env python3
"""One live operator: saved terrain, independent edits, real impaired UDP, evidence."""
import argparse
import asyncio
import contextlib
import hashlib
import json
import multiprocessing
import os
import random
import shutil
import signal
import struct
import time
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT.parent / "Voxy_Testing/world/dimensions/voxy/pressure/region"


def region(i):
    return (i % 10 * 4 - 20, i // 10 * 4 - 20)


def write_at(fd, data, position):
    pending = memoryview(data)
    while pending:
        count = os.pwrite(fd, pending, position)
        if count <= 0:
            raise OSError("Incomplete saved chunk write")
        pending = pending[count:]
        position += count


def writer(queue, ready, report):
    with contextlib.ExitStack() as owned:
        files, headers = [], []
        for i in range(100):
            rx, rz = region(i)
            file = owned.enter_context(
                (FIXTURE / f"r.{rx}.{rz}.mca").open("r+b", buffering=0)
            )
            files.append(file.fileno())
            headers.append(bytearray(os.pread(file.fileno(), 8192, 0)))
            if len(headers[-1]) != 8192:
                raise RuntimeError("Truncated pressure region header")
        ready.set()
        persisted = saved_bytes = appended = 0
        log = owned.enter_context(report.open("w"))
        while (batch := queue.get()) is not None:
            number, origin, edits = batch
            dirty = {}
            for i, slot, x, y, z in edits:
                if (i, slot) not in dirty:
                    location = struct.unpack_from(">I", headers[i], slot * 4)[0]
                    offset = (location >> 8) * 4096
                    prefix = os.pread(files[i], 5, offset)
                    length = struct.unpack_from(">I", prefix)[0]
                    if (
                        not location
                        or prefix[4] != 2
                        or length < 2
                        or length + 4 > (location & 255) * 4096
                    ):
                        raise RuntimeError("Invalid pressure chunk allocation")
                    raw = bytearray(
                        zlib.decompress(os.pread(files[i], length - 1, offset + 5))
                    )
                    marker = b"\x0c\x00\x04data\x00\x00\x01\x00"
                    offsets = []
                    cursor = 0
                    while (cursor := raw.find(marker, cursor)) >= 0:
                        offsets.append(cursor + len(marker))
                        cursor += len(marker) + 2048
                    if len(offsets) != 4:
                        raise RuntimeError("Unexpected fixture block storage")
                    dirty[i, slot] = raw, offsets
                raw, offsets = dirty[i, slot]
                local = x | z << 4 | (y % 16) << 8
                offset = offsets[y // 16] + local // 16 * 8 + 7 - (local % 16) // 2
                shift = local % 2 * 4
                value = 1 if (raw[offset] >> shift & 15) == 5 else 5
                raw[offset] = raw[offset] & ~(15 << shift) | value << shift
            for (i, slot), (raw, _) in dirty.items():
                payload = zlib.compress(raw, 1)
                location = struct.unpack_from(">I", headers[i], slot * 4)[0]
                offset = (location >> 8) * 4096
                sectors = (len(payload) + 4100) // 4096
                if sectors > (location & 255):
                    offset = (os.fstat(files[i]).st_size + 4095) // 4096 * 4096
                    os.ftruncate(files[i], offset + sectors * 4096)
                    location = offset // 4096 << 8 | sectors
                    appended += 1
                body = struct.pack(">I", len(payload) + 1) + b"\x02" + payload
                write_at(files[i], body, offset)
                write_at(files[i], struct.pack(">I", location), slot * 4)
                struct.pack_into(">I", headers[i], slot * 4, location)
                stamp = struct.unpack_from(">I", headers[i], 4096 + slot * 4)[0] + 1
                struct.pack_into(">I", headers[i], 4096 + slot * 4, stamp)
                write_at(files[i], struct.pack(">I", stamp), 4096 + slot * 4)
                saved_bytes += len(body) + 8
            for i in {i for i, _ in dirty}:
                os.fsync(files[i])
            persisted += len(edits)
            dirty.clear()
            log.write(
                json.dumps(
                    {
                        "elapsed": time.monotonic() - origin,
                        "offered_second": number,
                        "persisted": persisted,
                        "saved_bytes": saved_bytes,
                        "appended_chunks": appended,
                        "edits_sha256": hashlib.sha256(
                            json.dumps(sorted(edits)).encode()
                        ).hexdigest(),
                    }
                )
                + "\n"
            )
            log.flush()


class Link(asyncio.DatagramProtocol):
    def __init__(self, loss, bps, rtt, seed):
        self.loss, self.bps, self.delay = loss, bps, rtt / 2
        self.random = [random.Random(seed), random.Random(seed + 1)]
        self.next = [0.0, 0.0]
        self.stats = [
            dict(
                packets=0,
                bytes=0,
                dropped=0,
                delivered=0,
                min_delay_ms=None,
                max_delay_ms=0,
            )
            for _ in range(2)
        ]

    def forward(self, data, direction, transport, address):
        loop = asyncio.get_running_loop()
        now = loop.time()
        row = self.stats[direction]
        row["packets"] += 1
        row["bytes"] += len(data)
        self.next[direction] = max(now, self.next[direction]) + len(data) * 8 / self.bps
        if self.random[direction].random() < self.loss:
            row["dropped"] += 1
            return

        def deliver():
            transport.sendto(data, address)
            elapsed = (loop.time() - now) * 1000
            row["delivered"] += 1
            row["min_delay_ms"] = min(row["min_delay_ms"] or elapsed, elapsed)
            row["max_delay_ms"] = max(row["max_delay_ms"], elapsed)

        loop.call_at(self.next[direction] + self.delay, deliver)


class Receiver(asyncio.DatagramProtocol):
    def __init__(self, link, direction, server):
        self.link, self.direction, self.server = link, direction, server

    def datagram_received(self, data, address):
        if self.direction == 0:
            self.link.client = address
            self.link.forward(data, 0, self.link.up, self.server)
        elif hasattr(self.link, "client"):
            self.link.forward(data, 1, self.link.down, self.link.client)


def resources(pids):
    result = {
        "epoch": time.time(),
        "host": Path("/proc/meminfo").read_text(),
        "processes": {},
    }
    for pid in pids:
        try:
            base = Path("/proc") / pid
            cg = Path("/sys/fs/cgroup") / base.joinpath(
                "cgroup"
            ).read_text().strip().split("::")[-1].lstrip("/")
            result["processes"][pid] = {
                name: base.joinpath(name).read_text()
                for name in ("status", "stat", "io")
            }
            result["processes"][pid]["cgroup"] = {
                name: cg.joinpath(name).read_text()
                for name in (
                    "memory.current",
                    "memory.peak",
                    "memory.max",
                    "memory.swap.max",
                    "memory.events",
                    "cpu.stat",
                )
            }
        except OSError as error:
            result["processes"][pid] = {"error": str(error), "alive": base.exists()}
    return result


async def run(args):
    out = ROOT / "project_audit/load_results" / args.name
    out.mkdir(parents=True, exist_ok=False)
    cache = out / "cache"
    if args.warm_from:
        shutil.copytree(args.warm_from, cache)
    ports, links = [], []
    loop = asyncio.get_running_loop()
    server = args.server.rsplit(":", 1)
    server = server[0], int(server[1])
    for i in range(100):
        fraction = i / 99
        link = Link(
            0.5 + 0.4 * fraction,
            3_000_000 - 2_500_000 * fraction,
            0.3 + 0.7 * fraction,
            244 + i * 2,
        )
        link.up, _ = await loop.create_datagram_endpoint(
            lambda: Receiver(link, 1, server), local_addr=("127.0.0.1", 0)
        )
        link.down, _ = await loop.create_datagram_endpoint(
            lambda: Receiver(link, 0, server), local_addr=("127.0.0.1", 0)
        )
        ports.append("127.0.0.1:" + str(link.down.get_extra_info("sockname")[1]))
        links.append(link)
    (out / "ports.json").write_text(json.dumps(ports))
    binary = ROOT / "tools/load/target/release/voxy-load"
    metadata = {
        "started_epoch": time.time(),
        "driver_sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
        "operator_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "certificate_sha256": hashlib.sha256(args.cert.read_bytes()).hexdigest(),
        "pressure_seconds": args.seconds,
        "clients": 100,
        "fixture": str(FIXTURE),
        "full_view_nodes": 597,
        "zoom": args.zoom,
        "startup_demand": "missing terrain after DIM before world ACK; cached refresh after identity association; cold timings from actor first attempt",
        "pressure_cache": "mixed at simultaneous100 barrier; per-client cached_at_pressure recorded",
        "congestion_controller": "Quinn default BBR",
        "loss_each_direction": [0.5, 0.9],
        "rtt_ms": [300, 1000],
        "bandwidth_bps": [500000, 3000000],
    }
    (out / "run.json").write_text(json.dumps(metadata, indent=2) + "\n")
    queue, ready = multiprocessing.Queue(), multiprocessing.Event()
    saving = multiprocessing.Process(
        target=writer, args=(queue, ready, out / "saved-changes.jsonl")
    )
    saving.start()
    try:
        while not ready.is_set():
            if not saving.is_alive():
                raise RuntimeError("Saved writer failed before readiness")
            await asyncio.sleep(0.1)
        peer = await asyncio.create_subprocess_exec(
            str(binary),
            str(args.seconds + 30),
            str(out / "ports.json"),
            str(args.cert),
            str(cache),
            str(out / "clients.json"),
            *(["zoom"] if args.zoom else []),
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT,
        )
    except BaseException as error:
        metadata["failure"] = str(error) or type(error).__name__
        if saving.is_alive():
            saving.terminate()
            await asyncio.to_thread(saving.join)
        for link in links:
            link.up.close()
            link.down.close()
        (out / "run.json").write_text(json.dumps(metadata, indent=2) + "\n")
        raise
    identities = {}
    unverified = set(args.pids.split(","))
    mutation_finished = asyncio.Event()

    async def observe():
        with (out / "resources.jsonl").open("w") as log:
            while peer.returncode is None:
                sample = resources(
                    [*args.pids.split(","), str(peer.pid), str(saving.pid)]
                )
                log.write(json.dumps(sample) + "\n")
                log.flush()
                for pid in args.pids.split(","):
                    row = sample["processes"][pid]
                    failure = None
                    if "error" in row:
                        unverified.add(pid)
                        metadata.setdefault("observer_errors", []).append(
                            {
                                "pid": pid,
                                "epoch": sample["epoch"],
                                "error": row["error"],
                            }
                        )
                        if not row["alive"]:
                            failure = "Watched process exited"
                    else:
                        unverified.discard(pid)
                        group = row["cgroup"]
                        start_tick = row["stat"].rsplit(")", 1)[1].split()[19]
                        killed = int(
                            dict(
                                line.split()
                                for line in group["memory.events"].splitlines()
                            )["oom_kill"]
                        )
                        identity = (
                            start_tick,
                            group["memory.max"].strip(),
                            group["memory.swap.max"].strip(),
                            killed,
                        )
                        original = identities.setdefault(pid, identity)
                        if identity[:3] != original[:3]:
                            failure = (
                                "Watched process identity or existing ceiling changed"
                            )
                        elif killed > original[3]:
                            failure = "Watched cgroup recorded an OOM kill"
                    if failure:
                        metadata["failure"] = failure
                        metadata.setdefault("process_failures", []).append(
                            {"pid": pid, "epoch": sample["epoch"], "reason": failure}
                        )
                        if changing:
                            changing.cancel()
                        if peer.returncode is None:
                            peer.send_signal(signal.SIGINT)
                        return
                if not saving.is_alive() and not mutation_finished.is_set():
                    metadata["failure"] = (
                        "Saved writer exited before completing the mutation clock"
                    )
                    if changing:
                        changing.cancel()
                    if peer.returncode is None:
                        peer.send_signal(signal.SIGINT)
                    return
                (out / "network.json").write_text(
                    json.dumps(
                        [
                            {
                                "loss": link.loss,
                                "bps": link.bps,
                                "rtt_ms": link.delay * 2000,
                                "directions": link.stats,
                            }
                            for link in links
                        ],
                        indent=2,
                    )
                )
                await asyncio.sleep(1)

    async def edits():
        origin = time.monotonic()
        rng = random.Random(244)
        for second in range(args.seconds):
            await asyncio.sleep(max(0, origin + second - time.monotonic()))
            batch = set()
            while len(batch) < 300:
                i, x, z = rng.randrange(100), rng.randrange(512), rng.randrange(512)
                batch.add(
                    (i, x // 16 + z // 16 * 32, x % 16, rng.randrange(10, 20), z % 16)
                )
            queue.put((second, origin, batch))
        await asyncio.sleep(max(0, origin + args.seconds - time.monotonic()))
        mutation_finished.set()
        queue.put(None)
        await asyncio.to_thread(saving.join)
        commits = [
            json.loads(line)
            for line in (out / "saved-changes.jsonl").read_text().splitlines()
        ]
        metadata["persisted_before_deadline"] = max(
            (row["persisted"] for row in commits if row["elapsed"] <= args.seconds),
            default=0,
        )
        metadata["persisted_rate_during_pressure"] = (
            metadata["persisted_before_deadline"] / args.seconds
        )
        metadata["last_commit"] = commits[-1] if commits else None

    observing = asyncio.create_task(observe())
    changing = None

    def observation_finished(task):
        if not task.cancelled() and (error := task.exception()):
            metadata["failure"] = "Process observer failed: " + str(error)
            if changing:
                changing.cancel()
            if peer.returncode is None:
                peer.send_signal(signal.SIGINT)

    observing.add_done_callback(observation_finished)
    try:
        with (out / "clients.jsonl").open("w") as log:
            while line := await peer.stdout.readline():
                decoded = line.decode()
                log.write(decoded)
                log.flush()
                print(decoded, end="", flush=True)
                try:
                    event = json.loads(decoded)
                except json.JSONDecodeError:
                    continue
                if event.get("event") == "ready":
                    peer.stdin.write(b"START\n")
                    await peer.stdin.drain()
                if event.get("event") == "started":
                    if event["active"] != 100:
                        raise RuntimeError("Pressure requires 100 live clients")
                    metadata["pressure_start_epoch"] = time.time()
                    changing = asyncio.create_task(edits())
        metadata["driver_exit"] = await peer.wait()
        if changing:
            await changing
        else:
            raise RuntimeError("No simultaneous 100-client pressure interval")
        if observing.done():
            observing.result()
        if (
            metadata["driver_exit"]
            or metadata.get("failure")
            or unverified
            or metadata["persisted_rate_during_pressure"] < 300
        ):
            raise RuntimeError("Pressure workload or process health was not verified")
    except BaseException as error:
        metadata.setdefault("failure", str(error) or type(error).__name__)
        raise
    finally:
        if peer.returncode is None:
            peer.send_signal(signal.SIGINT)
            await peer.wait()
        if saving.is_alive():
            saving.terminate()
            await asyncio.to_thread(saving.join)
        observing.cancel()
        await asyncio.gather(observing, return_exceptions=True)
        metadata["unverified_watched_pids"] = sorted(unverified)
        for link in links:
            link.up.close()
            link.down.close()
        (out / "run.json").write_text(json.dumps(metadata, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["run"])
    parser.add_argument("--name")
    parser.add_argument("--server")
    parser.add_argument("--cert", type=Path)
    parser.add_argument("--seconds", type=int, default=120)
    parser.add_argument("--warm-from", type=Path)
    parser.add_argument("--pids", default="")
    parser.add_argument(
        "--zoom",
        action="store_true",
        help="separate quality case; full597-node demand remains the comparison default",
    )
    options = parser.parse_args()
    if FIXTURE.is_symlink() or FIXTURE.resolve() != FIXTURE:
        raise RuntimeError("Pressure fixture must have an exact non-symlink path")
    if (
        not options.name
        or "/" in options.name
        or not options.server
        or not options.cert
        or not all(pid.isdigit() and int(pid) > 0 for pid in options.pids.split(","))
        or options.seconds < 1
    ):
        parser.error(
            "run requires --name, --server, --cert and numeric --pids for the existing native and Java processes"
        )
    asyncio.run(run(options))
