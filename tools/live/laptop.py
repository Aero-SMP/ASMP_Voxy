"""Execute an authorized operator script over either independently pinned SSH link."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("script", type=Path)
    parser.add_argument("--save", type=Path)
    parser.add_argument("--quiet", action="store_true")
    parser.add_argument("--timeout", type=int, default=60)
    parser.add_argument(
        "--connection", choices=("primary", "secondary"), default="primary"
    )
    parser.add_argument(
        "--parameter", action="append", default=[], metavar="NAME=VALUE"
    )
    args = parser.parse_args()
    tools = Path(__file__).resolve().parent
    command = (
        "$ProgressPreference='SilentlyContinue';\n" + (tools / "common.ps1").read_text()
    )
    command += "\n& {\n" + args.script.read_text() + "\n}"
    for parameter in args.parameter:
        name, separator, value = parameter.partition("=")
        if not separator or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", name):
            parser.error("parameters must be NAME=VALUE with a simple parameter name")
        command += " -" + name + " '" + value.replace("'", "''") + "'"
    environment = {
        **os.environ,
        "VOXY_BACKUP_CONNECTION": "secondary" if args.connection == "secondary" else "",
    }
    try:
        result = subprocess.run(
            ["bash", str(tools / "ssh/connect.sh"), command],
            capture_output=True,
            text=True,
            timeout=args.timeout,
            env=environment,
        )
    except subprocess.TimeoutExpired:
        print(
            f"Laptop backup SSH command timed out after {args.timeout} seconds",
            file=sys.stderr,
        )
        return 124
    if result.returncode:
        print(result.stdout[:12000])
        print(result.stderr[:12000], file=sys.stderr)
        return result.returncode
    output = result.stdout.strip()
    try:
        output = json.dumps(json.loads(output), indent=2) + "\n"
    except json.JSONDecodeError:
        output += "\n"
    if args.save:
        args.save.parent.mkdir(parents=True, exist_ok=True)
        args.save.write_text(output)
    if not args.quiet:
        print(output[:16000], end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
