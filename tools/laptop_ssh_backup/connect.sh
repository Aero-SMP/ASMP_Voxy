#!/usr/bin/env bash
set -euo pipefail
state=/home/aerosmp/Desktop/Voxy_Testing/logs/laptop-backup
port=22023
alias_args=(-o HostKeyAlias=voxy-testing-laptop-backup)
if [[ -s "$state/tunnel-port" ]]; then
  read -r port < "$state/tunnel-port"
  [[ "$port" =~ ^[0-9]+$ ]] && ((port > 0 && port <= 65535)) || exit 1
fi
ssh_options=(-i /home/aerosmp/.ssh/id_ed25519 -o IdentitiesOnly=yes
  -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=3
  -o "UserKnownHostsFile=$state/host_known_hosts" "${alias_args[@]}")
probe() { ssh -n "${ssh_options[@]}" -p "$1" voxy-backup@127.0.0.1 'exit 0' >/dev/null 2>&1; }
if ! probe "$port"; then
  found=false
  # Some Windows OpenSSH builds omit the allocation notice. Discover only local
  # reverse listeners owned by our account; the pinned laptop key must match
  # before authentication or execution, so unrelated SSH endpoints are rejected.
  while read -r candidate; do
    if probe "$candidate"; then port=$candidate; found=true; break; fi
  done < <(ss -ltnpe | awk -v uid="$(id -u)" '$0 ~ "uid:"uid" " && $0 !~ /users:/ && $4 ~ /^127[.]0[.]0[.]1:/ { split($4, address, ":"); print address[2] }')
  "$found" || { echo 'Laptop backup SSH is reconnecting; no pinned endpoint available.' >&2; exit 255; }
  printf '%s\n' "$port" > "$state/tunnel-port"
fi
exec ssh "${ssh_options[@]}" -o ConnectTimeout=10 -p "$port" voxy-backup@127.0.0.1 "$@"
