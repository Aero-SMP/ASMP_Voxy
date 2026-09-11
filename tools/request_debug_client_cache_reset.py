#!/usr/bin/env python3
"""Request a one-shot .voxy deletion/restart from one debug client via its SSH updater.

The client polls build/libs over authenticated SSH. This does not touch the server
world/cache, restart any server, or clear a client's mods, options, or updater data.
Deletion is permanent. Run only for a player whose cache reset is authorized.
"""
import argparse
import os
from pathlib import Path
import re
import uuid


def request_reset(player: str, directory: Path) -> str:
    if re.fullmatch(r"[A-Za-z0-9_]{1,16}", player) is None:
        raise ValueError("invalid Minecraft player name")
    directory.mkdir(parents=True, exist_ok=True)
    request = str(uuid.uuid4())
    temporary = directory / f".{player}.{request}.tmp"
    try:
        with temporary.open("x") as output:
            output.write(request + "\n")
        os.replace(temporary, directory / f"{player}.request")
    finally:
        temporary.unlink(missing_ok=True)
    return request


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("player")
    arguments = parser.parse_args()
    directory = Path(__file__).resolve().parents[1] / "build/libs/debug-client-reset"
    request = request_reset(arguments.player, directory)
    print(f"Requested permanent .voxy reset and restart for {arguments.player}: {request}")
    print("Verify cache-reset-complete in the uploaded restart.log; request creation is not completion.")
