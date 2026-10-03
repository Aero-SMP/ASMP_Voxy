#!/usr/bin/env bash
set -euo pipefail
state=/home/aerosmp/Desktop/Voxy_Testing/logs/laptop-backup
port=22023
alias_args=()
if [[ -s "$state/tunnel-port" ]]; then
  read -r port < "$state/tunnel-port"
  [[ "$port" =~ ^[0-9]+$ ]] && ((port > 0 && port <= 65535)) || exit 1
  alias_args=(-o HostKeyAlias=voxy-testing-laptop-backup)
fi
exec ssh -i /home/aerosmp/.ssh/id_ed25519 -o IdentitiesOnly=yes \
  -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10 \
  -o UserKnownHostsFile=/home/aerosmp/Desktop/Voxy_Testing/logs/laptop-backup/host_known_hosts \
  "${alias_args[@]}" -p "$port" voxy-backup@127.0.0.1 "$@"
