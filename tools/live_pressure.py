#!/usr/bin/env python3
"""Live QUIC players with packet impairment and saved Minecraft mutation evidence.

This starts clients only. Server/client deployment and an observed real player are
required preflight evidence. It never starts or stops a Minecraft/native server.
"""
import argparse
import asyncio
import hashlib
import json
import os
import random
import signal
import socket
import struct
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLIENTS = 100


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + ".pending")
    temporary.write_text(json.dumps(value, indent=2) + "\n")
    temporary.replace(path)


class Link:
    """One shared physical uplink/downlink for foreground and background QUIC."""
    def __init__(self, number, loss, bandwidth, rtt_ms, cap_kbps, seed, capture):
        self.id, self.loss, self.bandwidth = number, loss, bandwidth
        self.delay = rtt_ms / 2000
        self.random = [random.Random(seed), random.Random(seed + 1)]
        self.available = [0.0, 0.0]
        self.channels, self.handles = {}, set()
        self.background_routes = {}
        self.cap = cap_kbps * 125
        self.background_clock, self.background_debt = None, 0.0
        self.background_max_debt = self.background_max_datagram = 0
        self.closed = False
        self.capture = capture
        self.stats = {
            name: [dict(packets=0, ip_bytes=0, dropped=0, delivered=0,
                        first_packet_ns=None, last_packet_ns=None,
                        delivered_ip_bytes=0, min_delay_ms=None, max_delay_ms=0,
                        pending_packets=0, pending_bytes=0, peak_pending_bytes=0)
                   for _ in range(2)]
            for name in ("foreground", "background")
        }

    def forward(self, name, direction, data, transport, address, timestamp_ns):
        if self.closed:
            return
        loop = asyncio.get_running_loop()
        now = loop.time()
        row = self.stats[name][direction]
        # Proxies are IPv4: count actual UDP payload plus IP and UDP headers.
        cost = len(data) + 28
        row["packets"] += 1
        row["ip_bytes"] += cost
        stamp = timestamp_ns
        row["first_packet_ns"] = row["first_packet_ns"] or stamp
        row["last_packet_ns"] = stamp
        if name == "background" and direction == 1:
            # Replayable per-datagram cap evidence: kernel epoch-ns, IPv4 bytes, client id.
            self.capture.write(struct.pack("<QII", timestamp_ns, cost, self.id))
        if name == "background" and direction == 1 and self.cap:
            clock = timestamp_ns / 1_000_000_000
            elapsed = 0 if self.background_clock is None else clock - self.background_clock
            self.background_debt = max(0, self.background_debt - elapsed * self.cap) + cost
            self.background_clock = clock
            self.background_max_debt = max(self.background_max_debt, self.background_debt)
            self.background_max_datagram = max(self.background_max_datagram, cost)
        # Lost packets consumed the link too. There is no idle burst credit.
        self.available[direction] = max(now, self.available[direction]) + cost * 8 / self.bandwidth
        if self.random[direction].random() < self.loss:
            row["dropped"] += 1
            return
        row["pending_packets"] += 1
        row["pending_bytes"] += cost
        row["peak_pending_bytes"] = max(row["peak_pending_bytes"], row["pending_bytes"])
        handle = None

        def deliver():
            self.handles.discard(handle)
            row["pending_packets"] -= 1
            row["pending_bytes"] -= cost
            if self.closed:
                return
            transport.sendto(data, address)
            elapsed = (loop.time() - now) * 1000
            row["delivered"] += 1
            row["delivered_ip_bytes"] += cost
            row["min_delay_ms"] = min(row["min_delay_ms"] or elapsed, elapsed)
            row["max_delay_ms"] = max(row["max_delay_ms"], elapsed)

        handle = loop.call_at(self.available[direction] + self.delay, deliver)
        self.handles.add(handle)

    def report(self):
        return dict(id=self.id, loss_each_direction=self.loss,
                    bandwidth_bps_each_direction=self.bandwidth,
                    propagation_rtt_ms=self.delay * 2000,
                    background_cap_ip_bytes_per_second=self.cap,
                    background_max_pacing_debt_bytes=self.background_max_debt,
                    background_max_ip_datagram_bytes=self.background_max_datagram,
                    background_envelope_bytes=17,
                    channels=self.stats,
                    upstream_ports={name: channel["upstream_port"] for name, channel in self.channels.items()},
                    background_server_port=self.channels["background"]["remote"][1]
                    if self.channels["background"]["remote"] else None,
                    socket_queue_drops=sum(channel[side].queue_drops for channel in self.channels.values()
                                           for side in ("up", "down")))

    def close(self):
        self.closed = True
        for handle in self.handles:
            handle.cancel()
        self.handles.clear()
        for channel in self.channels.values():
            for side in ("up", "down"):
                if side in channel:
                    channel[side].close()


class Receiver:
    """Read Linux kernel packet arrival stamps, independent of Python scheduling."""
    def __init__(self, link, name, direction):
        self.link, self.name, self.direction = link, name, direction
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.setblocking(False)
        self.timestamp_option = getattr(socket, "SO_TIMESTAMPNS", 35)
        self.overflow_option = getattr(socket, "SO_RXQ_OVFL", 40)
        self.socket.setsockopt(socket.SOL_SOCKET, self.timestamp_option, 1)
        self.socket.setsockopt(socket.SOL_SOCKET, self.overflow_option, 1)
        self.queue_drops = 0
        self.socket.bind(("127.0.0.1", 0))
        self.loop = asyncio.get_running_loop()
        self.loop.add_reader(self.socket.fileno(), self.receive)

    def sendto(self, data, address):
        self.socket.sendto(data, address)

    def get_extra_info(self, name):
        if name == "sockname":
            return self.socket.getsockname()
        raise KeyError(name)

    def close(self):
        self.loop.remove_reader(self.socket.fileno())
        self.socket.close()

    def receive(self):
        while True:
            try:
                data, ancillary, flags, address = self.socket.recvmsg(
                    65535, socket.CMSG_SPACE(16) + socket.CMSG_SPACE(4))
            except BlockingIOError:
                return
            if flags & (socket.MSG_TRUNC | socket.MSG_CTRUNC):
                raise RuntimeError("Packet-boundary evidence was truncated")
            stamp = None
            for level, kind, value in ancillary:
                if level == socket.SOL_SOCKET and kind == self.timestamp_option:
                    seconds, nanos = struct.unpack("@ll", value)
                    stamp = seconds * 1_000_000_000 + nanos
                elif level == socket.SOL_SOCKET and kind == self.overflow_option:
                    self.queue_drops = struct.unpack("@I", value)[0]
                    if self.queue_drops:
                        raise RuntimeError("UDP proxy's kernel queue lost packets outside the requested link impairment")
            if stamp is None:
                raise RuntimeError("Kernel did not supply a packet timestamp")
            self.datagram_received(data, address, stamp)

    def datagram_received(self, data, address, timestamp_ns):
        # Separate proxy sockets identify each channel. Plain QUIC may start
        # with zero when its fixed bit is greased, so that byte is not a tag.
        route = data[:17] if self.name == "background" else None
        if self.name == "background" and (
                len(data) <= 17 or route not in self.link.background_routes):
            raise RuntimeError(
                f"Invalid background UDP envelope: client={self.link.id} "
                f"direction={self.direction} bytes={len(data)}")
        channel = self.link.channels[self.name]
        client = self.link.background_routes[route] if route else channel["client"]
        if self.direction == 0:
            if client is None:
                client = address
                if route:
                    self.link.background_routes[route] = client
                else:
                    channel["client"] = client
            if address != client or channel["remote"] is None:
                raise RuntimeError("Unassociated virtual-client datagram")
            self.link.forward(self.name, 0, data, channel["up"], channel["remote"], timestamp_ns)
        elif address == channel["remote"] and client:
            self.link.forward(self.name, 1, data, channel["down"], client, timestamp_ns)


def process_snapshot(pid):
    base = Path("/proc") / str(pid)
    stat = base.joinpath("stat").read_text()
    cgroup = base.joinpath("cgroup").read_text().strip().split("::")[-1]
    group = Path("/sys/fs/cgroup") / cgroup.lstrip("/")
    names = ("memory.current", "memory.peak", "memory.max", "memory.swap.max",
             "memory.events", "cpu.stat")
    return dict(pid=pid, start_ticks=stat.rsplit(")", 1)[1].split()[19],
                cwd=str(base.joinpath("cwd").resolve()), status=base.joinpath("status").read_text(),
                io=base.joinpath("io").read_text(), cgroup_path=str(group),
                cgroup={name: group.joinpath(name).read_text() for name in names})


def process_identity(snapshot):
    group = snapshot["cgroup"]
    return (snapshot["start_ticks"], snapshot["cwd"], group["memory.max"].strip(),
            group["memory.swap.max"].strip(),
            int(dict(line.split() for line in group["memory.events"].splitlines())["oom_kill"]))


def control_argv(control, action, substitutions):
    return [argument.format_map(substitutions) for argument in control[action]]


async def control_command(control, action, substitutions, log):
    if action not in control:
        return
    argv = control_argv(control, action, substitutions)
    peer = await asyncio.create_subprocess_exec(*argv, stdout=asyncio.subprocess.PIPE,
                                              stderr=asyncio.subprocess.STDOUT)
    output, _ = await peer.communicate()
    log.write(json.dumps(dict(action=action, epoch=time.time(), argv=argv,
                             exit=peer.returncode, output=output.decode(errors="replace"))) + "\n")
    log.flush()
    if peer.returncode:
        raise RuntimeError(f"Mutation {action} command failed ({peer.returncode})")


def read_mutations(path, run_id):
    if not path.exists():
        return []
    events = []
    for line in path.read_text().splitlines():
        if "VOXY_PRESSURE " in line:
            line = line.split("VOXY_PRESSURE ", 1)[1]
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if event.get("run_id") == run_id:
            events.append(event)
    return events


class MutationEvidence:
    def __init__(self, path, run_id):
        self.path, self.run_id = path, run_id
        self.position, self.partial, self.events = 0, "", []

    def read(self):
        if not self.path.exists():
            return self.events
        with self.path.open() as stream:
            if self.path.stat().st_size < self.position:
                raise RuntimeError("Live mutation evidence was truncated")
            stream.seek(self.position)
            data = self.partial + stream.read()
            self.position = stream.tell()
        lines = data.split("\n")
        self.partial = lines.pop()
        for line in lines:
            if "VOXY_PRESSURE " in line:
                line = line.split("VOXY_PRESSURE ", 1)[1]
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if event.get("run_id") == self.run_id:
                self.events.append(event)
        return self.events


class NativeEvidence:
    def __init__(self, path):
        self.path = path
        self.position = path.stat().st_size
        self.partial, self.events = "", []

    def read(self):
        with self.path.open() as stream:
            if self.path.stat().st_size < self.position:
                raise RuntimeError("Native diagnostic trace was truncated during pressure")
            stream.seek(self.position)
            data = self.partial + stream.read()
            self.position = stream.tell()
        lines = data.split("\n")
        self.partial = lines.pop()
        for line in lines:
            for marker in ("VOXY_STREAM_SESSION", "VOXY_BACKGROUND_BATCH",
                           "VOXY_BACKGROUND_CONNECTED", "VOXY_BACKGROUND_STATS"):
                if marker in line:
                    values = dict(part.split("=", 1) for part in line.split(marker, 1)[1].split() if "=" in part)
                    self.events.append(dict(event=marker, **values))
                    break
        return self.events

    def verify_cadence(self, links, mutation_client_id=None):
        ports = {str(link.channels["foreground"]["upstream_port"]): link.id for link in links}
        sessions, previous, rows = {}, {}, []
        for event in self.events:
            if event["event"] == "VOXY_STREAM_SESSION":
                port = event["peer"].rsplit(":", 1)[1]
                if port in ports:
                    sessions[event["session"]] = ports[port]
            elif event["event"] == "VOXY_BACKGROUND_BATCH" and event.get("phase") == "start":
                session = event["session"]
                if session not in sessions:
                    continue
                stamp = int(event["monotonic_ns"])
                interval = int(event["interval_ms"])
                delta = stamp - previous[session] if session in previous else None
                rows.append(dict(client_id=sessions[session], session=session,
                                 sequence=int(event["seq"]), start_ns=stamp,
                                 previous_start_delta_ns=delta, interval_ms=interval))
                if delta is not None and delta < interval * 1_000_000:
                    raise RuntimeError(f"Client {sessions[session]} background batch interval was shorter than its setting")
                previous[session] = stamp
        if len(set(sessions.values())) != CLIENTS:
            raise RuntimeError("Native trace did not identify all 100 virtual player sessions")
        if mutation_client_id is not None and not any(
                row["client_id"] == mutation_client_id and
                row["previous_start_delta_ns"] is not None for row in rows):
            raise RuntimeError("No repeated background batches proved the mutation client's update interval")
        return rows


async def run(args):
    preflight = json.loads(args.preflight.read_text())
    if not preflight.get("real_client_verified") or not preflight.get("runtime_headroom_verified"):
        raise RuntimeError("Real client and runtime headroom must be verified by the deployment operator first")
    testing = Path(preflight["testing_root"]).resolve()
    if testing != ROOT.parent / "Voxy_Testing":
        raise RuntimeError("Pressure is restricted to Voxy_Testing")
    pids = [int(preflight[name]) for name in ("native_pid", "java_pid")]
    initial = {pid: process_snapshot(pid) for pid in pids}
    if any(row["cwd"] != str(testing) for row in initial.values()):
        raise RuntimeError("Watched process cwd does not identify Voxy_Testing")
    native_max = initial[pids[0]]["cgroup"]["memory.max"].strip()
    if native_max == "max" or int(native_max) > 1_073_741_824:
        raise RuntimeError("Existing native external 1 GB guard is missing")
    control = None if args.no_mutations else dict(
        start=[sys.executable, str(ROOT / "tools/voxy_testing_control.py"), "mutation-start", "{run}", "{seconds}", "300", "1000"],
        stop=[sys.executable, str(ROOT / "tools/voxy_testing_control.py"), "mutation-stop", "{run}"],
        restore=[sys.executable, str(ROOT / "tools/voxy_testing_control.py"), "mutation-restore", "{run}"],
        events_file=str(testing / "logs/voxy-pressure/{run}.jsonl"))
    if args.mutation_control:
        control = json.loads(args.mutation_control.read_text())
    if control and not args.native_log:
        raise RuntimeError("Full pressure proof requires --native-log with VOXY_BACKGROUND_TRACE=1")
    locations = [tuple(map(int, line.split())) for line in args.locations.read_text().splitlines()
                 if line.strip() and not line.lstrip().startswith("#")]
    if len(locations) != CLIENTS or len(set(locations)) != CLIENTS or any(len(item) != 2 for item in locations):
        raise RuntimeError("Locations must contain 100 distinct region_x region_z pairs")
    mutation_region = (args.mutation_block_x // 512, args.mutation_block_z // 512)
    if control and mutation_region not in locations:
        raise RuntimeError("One virtual client's desired region must contain the live mutation patch")
    out = ROOT / "project_audit/load_results" / args.name
    out.mkdir(parents=True, exist_ok=False)
    cache = out / "cache"
    if args.warm_from:
        # Clone/reflink is optional; never mutate a previous run's cache.
        import shutil
        shutil.copytree(args.warm_from, cache)
    host, port = args.server.rsplit(":", 1)
    host = socket.gethostbyname(host)
    remote = (host, int(port))
    binary = ROOT / "rust-server/target/release/live_pressure"
    metadata = dict(started_epoch=time.time(), clients=CLIENTS, server=args.server,
                    real_client_preflight=preflight, initial_processes=initial,
                    pressure_phase_seconds=args.phase_seconds, mutation_rates=[300, 1000],
                    background_interval_ms=args.interval_ms, background_cap_kbps=args.cap_kbps,
                    background_envelope_bytes=17, background_shared_udp_port=True,
                    cert_sha256=hashlib.sha256(args.cert.read_bytes()).hexdigest(),
                    driver_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),
                    operator_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                    locations=locations, base_y=args.base_y, height_sections=args.height_sections,
                    mutation_block=[args.mutation_block_x, args.mutation_block_y, args.mutation_block_z],
                    link_loss_each_direction=[0.5, 0.9], link_rtt_ms=[300, 1000],
                    shared_link_bandwidth_bps_each_direction=[500000, 3000000])
    write_json(out / "run.json", metadata)
    substitutions = dict(run=args.name, rates="300,1000", seconds=str(args.phase_seconds),
                         locations=str(args.locations.resolve()), dimension=args.dimension)
    events_path = Path(control["events_file"].format_map(substitutions)) if control else None
    mutation_evidence = MutationEvidence(events_path, args.name) if events_path else None
    native_evidence = NativeEvidence(args.native_log) if args.native_log else None
    links, ports = [], []
    capture = (out / "background-packets.bin").open("wb")
    peer, observer, mutation_started = None, None, False
    loop = asyncio.get_running_loop()
    previous_exception_handler = loop.get_exception_handler()
    def packet_failure(_loop, context):
        metadata["failure"] = "Packet impairment/capture failed: " + str(context.get("exception", context["message"]))
        write_json(out / "run.json", metadata)
        print(json.dumps(dict(event="packet_observer_failure", error=metadata["failure"])), flush=True)
        if peer and peer.returncode is None:
            peer.send_signal(signal.SIGINT)
    loop.set_exception_handler(packet_failure)
    duration = args.phase_seconds * 2 + args.settle_seconds
    try:
        for number, (rx, rz) in enumerate(locations):
            fraction = number / (CLIENTS - 1)
            link = Link(number, 0.5 + 0.4 * fraction, 3_000_000 - 2_500_000 * fraction,
                        300 + 700 * fraction, args.cap_kbps, args.seed + number * 2, capture)
            links.append(link)
            for name in ("foreground", "background"):
                channel = dict(remote=remote if name == "foreground" else None, client=None)
                link.channels[name] = channel
                channel["up"] = Receiver(link, name, 1)
                channel["down"] = Receiver(link, name, 0)
                channel["upstream_port"] = channel["up"].get_extra_info("sockname")[1]
            ports.append(" ".join(map(str, [number,
                f"127.0.0.1:{link.channels['foreground']['down'].get_extra_info('sockname')[1]}",
                f"127.0.0.1:{link.channels['background']['down'].get_extra_info('sockname')[1]}", rx, rz])))
        (out / "links.tsv").write_text("\n".join(ports) + "\n")
        (out / "locations.tsv").write_text(args.locations.read_text())
        peer = await asyncio.create_subprocess_exec(str(binary),
            "--links", str(out / "links.tsv"), "--cert", str(args.cert),
            "--cache", str(cache), "--dimension", args.dimension,
            "--duration", str(duration), "--setup-timeout", str(args.setup_timeout),
            "--interval-ms", str(args.interval_ms), "--cap-kbps", str(args.cap_kbps),
            "--base-y", str(args.base_y), "--height-sections", str(args.height_sections),
            "--mutation-block-x", str(args.mutation_block_x),
            "--mutation-block-y", str(args.mutation_block_y),
            "--mutation-block-z", str(args.mutation_block_z),
            "--zoom-seconds", str(args.zoom_seconds),
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
            limit=1024 * 1024)
        metadata["driver_pid"] = peer.pid
        identities = {pid: process_identity(row) for pid, row in initial.items()}

        async def observe():
            mutation_offset = native_offset = 0
            with (out / "resources.jsonl").open("w") as log, \
                    (out / "mutations-observed.jsonl").open("w") as mutation_log, \
                    (out / "native-background.jsonl").open("w") as native_log:
                while peer.returncode is None:
                    rows = {pid: process_snapshot(pid) for pid in pids}
                    for pid, row in rows.items():
                        if process_identity(row) != identities[pid]:
                            raise RuntimeError(f"Watched Testing process {pid} exited, restarted, changed its ceiling, or recorded OOM")
                    try:
                        driver = process_snapshot(peer.pid)
                    except FileNotFoundError:
                        driver = dict(pid=peer.pid, alive=False)
                    log.write(json.dumps(dict(epoch=time.time(), processes=rows,
                                             driver_process=driver,
                                             operator_process=process_snapshot(os.getpid()),
                                             host_meminfo=Path("/proc/meminfo").read_text())) + "\n")
                    log.flush()
                    write_json(out / "network.json", [link.report() for link in links])
                    if events_path:
                        mutations = mutation_evidence.read()
                        incoming_events = mutations[mutation_offset:]
                        for event in incoming_events:
                            mutation_log.write(json.dumps(event) + "\n")
                        mutation_offset = len(mutations)
                        mutation_log.flush()
                        if any(event.get("event") == "error" or event.get("evidence_failed") for event in incoming_events):
                            raise RuntimeError("Live Minecraft mutation writer reported failure")
                    if native_evidence:
                        native = native_evidence.read()
                        for event in native[native_offset:]:
                            native_log.write(json.dumps(event) + "\n")
                        native_offset = len(native)
                        native_log.flush()
                    await asyncio.sleep(1)

        observer = asyncio.create_task(observe())
        def observer_done(task):
            if not task.cancelled() and task.exception() and peer.returncode is None:
                metadata["failure"] = str(task.exception())
                peer.send_signal(signal.SIGINT)
        observer.add_done_callback(observer_done)
        async def copy_errors():
            with (out / "driver-stderr.log").open("wb") as log:
                while data := await peer.stderr.read(65536):
                    log.write(data)
                    log.flush()
        errors = asyncio.create_task(copy_errors())
        with (out / "clients.jsonl").open("w") as log, (out / "control.jsonl").open("w") as control_log:
            while line := await peer.stdout.readline():
                decoded = line.decode(errors="replace")
                log.write(decoded)
                log.flush()
                event = json.loads(decoded)
                print(json.dumps({key: value for key, value in event.items() if key != "clients"}), flush=True)
                if event["event"] == "background_endpoint":
                    prefix = bytes.fromhex(event["envelope"])
                    if len(prefix) != 16:
                        raise RuntimeError("Invalid background session envelope identity")
                    links[event["id"]].background_routes[b"\0" + prefix] = None
                    links[event["id"]].channels["background"]["remote"] = remote
                    peer.stdin.write(f"BG {event['id']}\n".encode())
                    await peer.stdin.drain()
                elif event["event"] == "ready":
                    if event["active"] != CLIENTS or event["served"] != CLIENTS:
                        raise RuntimeError("All 100 clients must be active and have usable served terrain")
                    peer.stdin.write(b"START\n")
                    await peer.stdin.drain()
                elif event["event"] == "started":
                    if event["active"] != CLIENTS:
                        raise RuntimeError("Not all 100 clients were live when pressure began")
                    metadata["pressure_start_epoch"] = time.time()
                    if control:
                        mutation_started = True
                        await control_command(control, "start", substitutions, control_log)
                elif event["event"] == "finished":
                    metadata["client_result"] = event
                    peer.stdin.close()
                elif event["event"] == "failure":
                    metadata["driver_failure"] = event["error"]
                    # Tokio's stdin reader is blocking underneath; EOF permits runtime shutdown.
                    peer.stdin.close()
            metadata["driver_exit"] = await peer.wait()
            await errors
            if control and mutation_started:
                await control_command(control, "stop", substitutions, control_log)
                await control_command(control, "restore", substitutions, control_log)
                mutations = mutation_evidence.read()
                write_json(out / "mutations.json", mutations)
                if any(event.get("event") == "error" or event.get("evidence_failed") for event in mutations):
                    raise RuntimeError("Minecraft mutation workload or evidence recording failed")
                summaries = {}
                for rate in (300, 1000):
                    phase = [event for event in mutations if event.get("phase") == rate]
                    completed = [event for event in phase if event.get("event") == "phase_completed"]
                    completion = max(completed, key=lambda event: event.get("changed_total", 0), default=None)
                    if not completion or not completion.get("last_change_elapsed_seconds"):
                        raise RuntimeError(f"Missing actual completed {rate} changes/s phase evidence")
                    changed = completion["changed_total"]
                    elapsed = completion["last_change_elapsed_seconds"]
                    achieved = changed / elapsed
                    saved = max((event.get("saved_total", 0) for event in phase), default=0)
                    saved_by_deadline = max((event.get("saved_total", 0) for event in phase
                        if event.get("phase_elapsed_seconds", 0) <= args.phase_seconds), default=0)
                    summaries[rate] = dict(actual_changed_total=changed,
                        requested_phase_seconds=args.phase_seconds, actual_phase_seconds=elapsed,
                        achieved_changed_rate_per_second=achieved,
                        completed_saved_by_phase_deadline=saved_by_deadline,
                        saved_rate_before_deadline=saved_by_deadline / args.phase_seconds,
                        completed_saved_total_after_settle=saved)
                    if elapsed < args.phase_seconds or achieved < rate or saved < changed:
                        raise RuntimeError(f"Did not prove {rate} actual changes/s and their eventual completed saves")
                metadata["mutation_phase_proof"] = summaries
                if not any(event.get("event") == "restored" for event in mutations):
                    raise RuntimeError("Minecraft pressure patch restoration was not confirmed")
                mutation_started = False
        if observer.done():
            observer.result()
        if metadata["driver_exit"] or metadata.get("failure") or "pressure_start_epoch" not in metadata:
            raise RuntimeError("100-client pressure interval failed; inspect saved evidence")
        if native_evidence:
            # Keep every proxy and packet capture alive until the peer has drained
            # its transport too. Process exit alone does not include server closes.
            ports = {str(link.channels["foreground"]["upstream_port"]): link.id for link in links}
            sessions, closed, position = {}, {}, 0
            while True:
                events = native_evidence.read()
                for event in events[position:]:
                    if event["event"] == "VOXY_STREAM_SESSION":
                        port = event["peer"].rsplit(":", 1)[1]
                        if port in ports:
                            sessions[event["session"]] = ports[port]
                    elif event["event"] == "VOXY_BACKGROUND_STATS":
                        closed[event["session"]] = event
                position = len(events)
                if len(set(sessions.values())) == CLIENTS and sessions.keys() <= closed.keys():
                    break
                if metadata.get("failure"):
                    raise RuntimeError(metadata["failure"])
                await asyncio.sleep(0.1)
            # Yield once to consume datagrams already timestamped by the kernel.
            await asyncio.sleep(0)
            totals = [dict(ip_bytes=0, datagrams=0) for _ in links]
            for session, client in sessions.items():
                for field in totals[client]:
                    totals[client][field] += int(closed[session][field])
            for link, total in zip(links, totals):
                captured = link.stats["background"][1]
                if total["ip_bytes"] != captured["ip_bytes"] or total["datagrams"] != captured["packets"]:
                    raise RuntimeError(f"Background capture and native teardown ledger differ for client {link.id}")
            metadata["background_teardown_ledgers"] = totals
        final = metadata.get("client_result", {})
        if final.get("active") != CLIENTS or final.get("served") != CLIENTS or final.get("integrity_failures"):
            raise RuntimeError("Final 100-client health, served terrain or integrity validation failed")
        if control and not final.get("mutation_changes"):
            raise RuntimeError("No virtual client received an actual background replacement of the live mutation patch")
        if native_evidence:
            native_evidence.read()
            metadata["background_batch_cadence"] = native_evidence.verify_cadence(
                links, locations.index(mutation_region) if control else None)
        # The packet-boundary observer includes all server-sent background IP traffic.
        if args.cap_kbps:
            for link in links:
                if link.background_max_debt > link.background_max_datagram + 28:
                    raise RuntimeError(f"Client {link.id} exceeded its actual background wire cap")
        metadata["verified"] = not args.no_mutations
        metadata["client_workload_verified"] = True
    except BaseException as error:
        metadata["failure"] = str(error) or type(error).__name__
        raise
    finally:
        if peer and peer.returncode is None:
            peer.send_signal(signal.SIGINT)
            peer.stdin.close()
            await peer.wait()
        if observer:
            observer.cancel()
            await asyncio.gather(observer, return_exceptions=True)
        if control and mutation_started:
            with (out / "control.jsonl").open("a") as log:
                for action in ("stop", "restore"):
                    try:
                        await control_command(control, action, substitutions, log)
                    except Exception as error:
                        metadata.setdefault("cleanup_failures", []).append(str(error))
        if events_path:
            write_json(out / "mutations.json", read_mutations(events_path, args.name))
        if native_evidence:
            try:
                write_json(out / "native-background.json", native_evidence.read())
            except Exception as error:
                metadata.setdefault("cleanup_failures", []).append(str(error))
        write_json(out / "network.json", [link.report() for link in links])
        for link in links:
            link.close()
        capture.close()
        with (out / "background-packets.bin").open("rb") as stream:
            metadata["background_packet_capture_sha256"] = hashlib.file_digest(stream, "sha256").hexdigest()
        metadata["background_packet_capture_bytes"] = (out / "background-packets.bin").stat().st_size
        loop.set_exception_handler(previous_exception_handler)
        metadata["finished_epoch"] = time.time()
        write_json(out / "run.json", metadata)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--server", required=True)
    parser.add_argument("--cert", type=Path, required=True)
    parser.add_argument("--preflight", type=Path, required=True)
    parser.add_argument("--native-log", type=Path, help="Native stderr log with VOXY_BACKGROUND_TRACE=1 for actual batch-cadence proof")
    parser.add_argument("--locations", type=Path, required=True)
    parser.add_argument("--dimension", required=True)
    parser.add_argument("--mutation-control", type=Path)
    parser.add_argument("--no-mutations", action="store_true", help="Observe-only case; cannot prove mutation pressure")
    parser.add_argument("--warm-from", type=Path)
    parser.add_argument("--phase-seconds", type=int, default=60)
    parser.add_argument("--settle-seconds", type=int, default=30)
    parser.add_argument("--setup-timeout", type=int, default=3600)
    parser.add_argument("--interval-ms", type=int, default=1000)
    parser.add_argument("--cap-kbps", type=int, default=1000)
    parser.add_argument("--base-y", type=int, default=0)
    parser.add_argument("--height-sections", type=int, default=2)
    parser.add_argument("--mutation-block-x", type=int, default=0)
    parser.add_argument("--mutation-block-y", type=int, default=312)
    parser.add_argument("--mutation-block-z", type=int, default=0)
    parser.add_argument("--zoom-seconds", type=int, default=10)
    parser.add_argument("--seed", type=int, default=244)
    options = parser.parse_args()
    if not options.name or "/" in options.name or options.name in (".", ".."):
        parser.error("--name must be a new result directory name")
    if options.phase_seconds < 1 or options.setup_timeout < 1 or options.settle_seconds < 0:
        parser.error("Invalid live observation duration")
    if options.interval_ms < 1000 or options.cap_kbps < 0 or options.height_sections < 1 or options.zoom_seconds < 1:
        parser.error("Invalid interval, bandwidth, height or zoom")
    asyncio.run(run(options))
