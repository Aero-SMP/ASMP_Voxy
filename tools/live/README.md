# Live operator tools

These maintained tools are part of the project's source inventory. They operate
on the authorized testing profile and existing pressure runs. They are not an
automated test suite.

Read current client status through the primary SSH connection:

```sh
python3 tools/live/laptop.py tools/live/client_status.ps1 --connection primary --save project_audit/live_client/current.json --quiet
```

Read both helper identities through the independently pinned secondary connection:

```sh
python3 tools/live/laptop.py tools/live/backup_status.ps1 --connection secondary --save project_audit/live_client/backups.json --quiet
```

Observe a genuine update, without installing a jar or requesting a restart:

```sh
python3 tools/live/laptop.py tools/live/client_update_observe.ps1 --parameter Seconds=240 --timeout 280 --save project_audit/live_client/update-monitor.json --quiet
```

The observer captures the original process handle and start time, reads shared
starting/ready/status files and the installed jar hash, and writes one JSONL row
per observation to `.voxy/updater/client-update-observations.jsonl`. A completed
observation interval is not a successful update by itself; the markers, actual
process identities and fresh renderer status provide that evidence.

`laptop_transfer.py` performs a pinned SFTP get or put within the testing profile.
Its local path must stay within this project. `laptop.py` supports named
PowerShell parameters with `--parameter NAME=VALUE`; parameter values are passed
as PowerShell string literals. `common.ps1` is included by that wrapper and owns
the shared file readers and actual client/helper identity queries. No tool
reads JVM launch arguments or authentication tokens.

`client_counters.ps1` observes existing telemetry without changing controls.
`updater_command_status.ps1` reads selected nonsecret SSH error lines.
`ssh_reachability.ps1` checks public DNS and TCP 22 without authentication.
`client_update_progress.ps1` reads the latest completed observer row.
`client_screenshot.ps1` requests a normal screenshot and restores controls.
Its optional `Clipping` and `Rendering` parameters accept `true`, `false`, or
`unchanged` for an explicitly coordinated visual comparison at the same pose.
Setting changes and screenshot requests use separate writes: the client captures
the preceding framebuffer when it reads the screenshot action in a tick.
`InspectPixel=X,Y` additionally requests a selected-terrain ray inspection at
physical screenshot coordinates. The operator requires a fresh rendered-frame
inspection before requesting the screenshot and saves the corresponding owner
and hash JSON alongside the image in the updater state directory.
`client_frames.ps1` requests four separate stock ten-second F3+L captures only
after checking the actual Minecraft window is foreground. Any temporary input
thread links are detached in `finally`, and focus is checked between captures.
`client_allocation.ps1` requests a separate stock JFR capture; the retained
`minimal-allocation.jfc` configuration matches the baseline settings.
`analyze_allocation.py` streams plain or gzip-compressed stock JFR JSON and
distinguishes weighted stack attribution from exact whole-thread counters.
Export allocation JSON with `jfr print --json --stack-depth 64 --events
jdk.ObjectAllocationSample,jdk.ThreadAllocationStatistics RECORDING.jfr`.
Use the same exported stack depth for comparisons: the default five-frame
printer view can omit Voxy callers below native or mod rendering methods.
The analyzer records both decimal MB/s and binary MiB/s.
Its `--frames --frame-receipt RECEIPT` mode accepts a directory containing the
downloaded stock frame ZIPs, verifies the capture hash/count, and calculates FPS
and nearest-rank p99.5 from `client/metrics/ticking.csv` nanoseconds.
Use a full stack export for Voxy attribution; a five-frame export can miss Voxy
callers beneath native or third-party rendering code and substantially undercount.
`client_cached_zoom.ps1` is an explicit live diagnostic that temporarily disables
terrain networking, captures FOV 30, and restores its original controls.
Profiling and control changes require coordination with the live client owner;
they must not overlap a comparison intended to measure ordinary frame behavior.

Observe an existing pressure run with its actual native PID:

```sh
python3 tools/live/pressure_observe.py project_audit/load_results/RUN --native-pid PID
```

That reader never starts, stops or restarts the workload. It reads newly appended
client events incrementally and retains its cursor in the run's evidence folder.
An absent 100-client gate means the saved-mutation pressure clock has not started.

The counted SSH implementation and connector are under `ssh/`. Private keys,
host pins, tunnel state and the two deployed helper processes retain their
existing locations. Historical migration, failed-candidate and one-time scripts
under `.verification/ops` are evidence only; new reusable operator behavior
belongs here.
