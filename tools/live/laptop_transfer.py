"""Pinned SFTP transfer restricted to the authorized testing profile."""

import argparse
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("get", "put"))
    parser.add_argument("local", type=Path)
    parser.add_argument("profile_relative")
    parser.add_argument("--timeout", type=int, default=60)
    parser.add_argument(
        "--connection", choices=("primary", "secondary"), default="primary"
    )
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[2]
    local = args.local.resolve()
    relative = args.profile_relative.replace("\\", "/")
    if not local.is_relative_to(project):
        parser.error("local transfer path must stay within this project")
    if any(character in str(local) + relative for character in '\r\n"'):
        parser.error("transfer paths cannot contain quotes or newlines")
    if relative.startswith("/") or ":" in relative or ".." in Path(relative).parts:
        parser.error("remote transfer path must stay within the testing profile")
    state = Path("/home/aerosmp/Desktop/Voxy_Testing/logs/laptop-backup")
    if args.connection == "secondary":
        state /= "secondary"
    remote = (
        "/C:/Users/USER/AppData/Roaming/ModrinthApp/profiles/Aero SMP (1)/" + relative
    )
    ssh = [
        "sftp",
        "-b",
        "-",
        "-i",
        "/home/aerosmp/.ssh/id_ed25519",
        "-o",
        "IdentitiesOnly=yes",
        "-o",
        "BatchMode=yes",
        "-o",
        "StrictHostKeyChecking=yes",
        "-o",
        "HostKeyAlias=voxy-testing-laptop-backup"
        + ("-secondary" if args.connection == "secondary" else ""),
        "-o",
        "UserKnownHostsFile=" + str(state / "host_known_hosts"),
        "-P",
        (state / "tunnel-port").read_text().strip(),
        "voxy-backup@127.0.0.1",
    ]
    source, destination = (
        (remote, str(local)) if args.operation == "get" else (str(local), remote)
    )
    result = subprocess.run(
        ssh,
        input=f'{args.operation} "{source}" "{destination}"\n',
        text=True,
        capture_output=True,
        timeout=args.timeout,
    )
    print(result.stdout)
    print(result.stderr)
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
