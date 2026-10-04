#!/usr/bin/env bash
set -euo pipefail
connection=${1:-primary}
shift || true
case "$connection" in
  primary) export VOXY_BACKUP_CONNECTION= ;;
  secondary) export VOXY_BACKUP_CONNECTION=secondary ;;
  *) echo 'Choose primary or secondary.' >&2; exit 2 ;;
esac
export VOXY_BACKUP_METADATA=pc-backup
exec bash "$(dirname -- "$0")/../laptop_ssh_backup/connect.sh" "$@"
