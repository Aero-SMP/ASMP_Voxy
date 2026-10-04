#!/usr/bin/env python3
"""Authenticated local RCON commands restricted to the existing Testing server."""
import argparse
import json
import re
import socket
import struct
from pathlib import Path

TESTING = Path("/home/aerosmp/Desktop/Voxy_Testing")


def properties(path):
    values = {}
    for line in path.read_text(encoding="latin-1").splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if not separator:
            key, separator, value = line.partition(":")
        if separator:
            # The generated server.properties uses = and Java property escapes.
            def decode(match):
                escaped = match.group(1)
                if escaped.startswith("u"):
                    return chr(int(escaped[1:], 16))
                return dict(t="\t", r="\r", n="\n", f="\f").get(escaped, escaped)
            values[key.strip()] = re.sub(r"\\(u[0-9a-fA-F]{4}|.)", decode, value.strip())
    return values


def listener_belongs_to_testing(port):
    inodes = set()
    for name in ("tcp", "tcp6"):
        for line in Path("/proc/net", name).read_text().splitlines()[1:]:
            row = line.split()
            if row[3] == "0A" and int(row[1].rsplit(":", 1)[1], 16) == port:
                inodes.add(row[9])
    if not inodes:
        return False
    for process in Path("/proc").iterdir():
        if not process.name.isdigit():
            continue
        try:
            if process.joinpath("cwd").resolve(strict=True) != TESTING:
                continue
            for descriptor in process.joinpath("fd").iterdir():
                target = descriptor.readlink().as_posix()
                if target.startswith("socket:[") and target[8:-1] in inodes:
                    return True
        except (OSError, RuntimeError):
            continue
    return False


def receive_exact(connection, count):
    result = bytearray()
    while len(result) < count:
        part = connection.recv(count - len(result))
        if not part:
            raise RuntimeError("Testing RCON closed before its response completed")
        result.extend(part)
    return result


def receive(connection):
    length = struct.unpack("<i", receive_exact(connection, 4))[0]
    if not 10 <= length <= 4 * 1024 * 1024:
        raise RuntimeError("Invalid RCON frame length")
    payload = receive_exact(connection, length)
    if payload[-2:] != b"\0\0":
        raise RuntimeError("Invalid RCON frame terminator")
    request_id, kind = struct.unpack_from("<ii", payload)
    return request_id, kind, payload[8:-2].decode("utf-8", errors="replace")


def send(connection, request_id, kind, text):
    body = struct.pack("<ii", request_id, kind) + text.encode("utf-8") + b"\0\0"
    connection.sendall(struct.pack("<i", len(body)) + body)


def command(text):
    if not text or any(character in text for character in "\r\n\0"):
        raise RuntimeError("A single nonempty console command is required")
    if TESTING.is_symlink() or TESTING.resolve() != TESTING:
        raise RuntimeError("Testing path must not be a symlink")
    config = properties(TESTING / "server.properties")
    if config.get("server-port") != "25587" or config.get("enable-rcon") != "true":
        raise RuntimeError("Testing MC port/RCON configuration does not match the approved target")
    port = int(config.get("rcon.port", "25575"))
    password = config.get("rcon.password", "")
    if not password or not listener_belongs_to_testing(port):
        raise RuntimeError("Testing RCON credential/listener ownership could not be verified")
    with socket.create_connection(("127.0.0.1", port), timeout=10) as connection:
        send(connection, 1, 3, password)
        while True:
            request_id, kind, _ = receive(connection)
            if request_id == -1:
                raise RuntimeError("Testing RCON authentication failed")
            if request_id == 1 and kind == 2:
                break
        send(connection, 2, 2, text)
        request_id, kind, response = receive(connection)
        if request_id != 2 or kind != 0:
            raise RuntimeError("Unexpected Testing RCON command response")
        return response


def run_id(text):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", text) or text in (".", ".."):
        raise argparse.ArgumentTypeError("Run id must contain only letters, numbers, underscores, dots or dashes")
    return text


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    direct = commands.add_parser("command")
    direct.add_argument("text")
    start = commands.add_parser("mutation-start")
    start.add_argument("run", type=run_id)
    start.add_argument("seconds", type=int)
    start.add_argument("rates", nargs="+", type=int)
    for action in ("mutation-stop", "mutation-restore"):
        item = commands.add_parser(action)
        item.add_argument("run", type=run_id)
    args = parser.parse_args()
    if args.action == "command":
        text = args.text
    elif args.action == "mutation-start":
        if args.seconds < 1 or args.rates != [300, 1000]:
            parser.error("Mutation pressure requires positive phase seconds and rates 300 1000")
        text = f"voxyload start {args.run} {args.seconds} 300 1000"
    else:
        text = f"voxyload {'stop' if args.action == 'mutation-stop' else 'restore'} {args.run}"
    try:
        print(command(text), flush=True)
    except Exception as error:
        print(json.dumps(dict(error=str(error))))
        raise SystemExit(1)
