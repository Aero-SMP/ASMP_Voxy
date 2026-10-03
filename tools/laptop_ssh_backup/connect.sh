#!/usr/bin/env bash
set -euo pipefail
exec ssh -i /home/aerosmp/.ssh/id_ed25519 -o IdentitiesOnly=yes \
  -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10 \
  -o UserKnownHostsFile=/home/aerosmp/Desktop/Voxy_Testing/logs/laptop-backup/host_known_hosts \
  -p 22023 voxy-backup@127.0.0.1 "$@"
