#!/usr/bin/env python3
"""External Voxy-only watchdog plus a kernel memory ceiling, scoped to this instance."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

ROOT = Path('/home/aerosmp/Desktop/Voxy_Testing')
LIMIT = 1_000_000_000
LOG = ROOT / 'logs/voxy-memory-guard.jsonl'


def process_identity(pid):
    try:
        return Path(f'/proc/{pid}/stat').read_text().split(') ', 1)[1].split()[19]
    except OSError:
        return None


def backend_child(watch_pid, expected, command):
    # The service owns a second observer so even SIGKILL of the outer watchdog
    # cannot leave the native process running after its Java supervisor exits.
    process = subprocess.Popen(command)
    try:
        while process.poll() is None:
            if process_identity(watch_pid) != expected:
                process.kill()
                break
            time.sleep(.05)
        return process.wait()
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()


def record(**fields):
    fields['time'] = time.time()
    LOG.parent.mkdir(parents=True, exist_ok=True)
    with LOG.open('a') as stream:
        stream.write(json.dumps(fields, sort_keys=True) + '\n')


def run_limited(command, limit=LIMIT, test=False):
    unit = f'voxy-testing-rust-{os.getpid()}' + ('-verification' if test else '') + '.service'
    runner = subprocess.Popen([
        'systemd-run', '--user', '--quiet', '--wait', '--pipe', '--unit=' + unit,
        '-p', 'Type=exec', '-p', 'KillMode=control-group', '-p', 'OOMPolicy=kill',
        '-p', f'MemoryMax={limit}', '-p', 'MemorySwapMax=0',
        '-p', f'WorkingDirectory={ROOT}', '-p', 'Environment=MALLOC_ARENA_MAX=2',
        '--', sys.executable, str(Path(__file__).resolve()), '--backend-child',
        str(os.getpid()), process_identity(os.getpid()), *command,
    ])
    killed = False
    group = None
    peak = 0
    oom_kills = 0
    parent = os.getppid()
    parent_identity = process_identity(parent)

    def stop(signum=None, frame=None):
        nonlocal killed
        killed = True
        subprocess.run(['systemctl', '--user', 'kill', '--kill-whom=all', '--signal=SIGKILL', unit],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        while runner.poll() is None:
            if killed or process_identity(parent) != parent_identity:
                stop()
            if group is None:
                result = subprocess.run(['systemctl', '--user', 'show', unit, '-p', 'ControlGroup', '--value'],
                                        text=True, capture_output=True)
                relative = result.stdout.strip()
                if relative:
                    candidate = Path('/sys/fs/cgroup') / relative.lstrip('/')
                    try:
                        effective = int((candidate / 'memory.max').read_text())
                        swap = (candidate / 'memory.swap.max').read_text().strip()
                        oom_group = (candidate / 'memory.oom.group').read_text().strip()
                    except (OSError, ValueError):
                        time.sleep(.05)
                        continue
                    if effective > limit or swap != '0' or oom_group != '1':
                        stop()
                        raise RuntimeError('Memory enforcement verification failed; backend killed')
                    group = candidate
                    record(event='LIMIT_ACTIVE', unit=unit, limit_bytes=limit,
                           effective_limit_bytes=effective, cgroup=str(group), verification=test)
            if group is not None:
                try:
                    current = int((group / 'memory.current').read_text())
                    peak = max(peak, current)
                    events = dict(line.split() for line in (group / 'memory.events').read_text().splitlines())
                    oom_kills = max(oom_kills, int(events.get('oom_kill', 0)))
                    for text in (group / 'cgroup.procs').read_text().splitlines():
                        pid = int(text)
                        status = Path(f'/proc/{pid}/status').read_text()
                        resident = next(int(line.split()[1]) * 1024 for line in status.splitlines()
                                        if line.startswith('VmRSS:'))
                        if resident >= limit:
                            record(event='EXTERNAL_KILL', unit=unit, pid=pid, resident_bytes=resident)
                            stop()
                except (OSError, StopIteration):
                    pass
            time.sleep(.05)
        result = runner.wait()
        if group is not None:
            try:
                events = dict(line.split() for line in (group / 'memory.events').read_text().splitlines())
                oom_kills = max(oom_kills, int(events.get('oom_kill', 0)))
                peak = max(peak, int((group / 'memory.peak').read_text()))
            except OSError:
                pass
        record(event='EXIT', unit=unit, code=result, peak_bytes=peak, oom_kills=oom_kills,
               external_stop=killed, verification=test)
        if test:
            outcome = subprocess.run(['systemctl', '--user', 'show', unit, '-p', 'Result', '--value'],
                                     capture_output=True, text=True).stdout.strip()
            if result == 0 or outcome != 'oom-kill':
                raise RuntimeError(f'Expected external OOM kill, got {result=} {outcome=}')
            print(json.dumps({'verified': True, 'result': outcome, 'exit': result, 'test_limit_bytes': limit,
                              'configured_limit_bytes': LIMIT, 'peak_bytes': peak}))
            return 0
        return result
    finally:
        stop()
        try:
            runner.wait(timeout=15)
        except subprocess.TimeoutExpired:
            runner.kill()
        subprocess.run(['systemctl', '--user', 'reset-failed', unit],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == '__main__':
    if len(sys.argv) >= 5 and sys.argv[1] == '--backend-child':
        sys.exit(backend_child(int(sys.argv[2]), sys.argv[3], sys.argv[4:]))
    if sys.argv[1:] == ['--verify-kill']:
        sys.exit(run_limited([sys.executable, '-c',
                             'import time; data=bytearray(96*1024*1024); time.sleep(10)'],
                            64 * 1024 * 1024, test=True))
    if Path.cwd().resolve() != ROOT or len(sys.argv) != 6 or sys.argv[2] != '--config' \
            or Path(sys.argv[3]).resolve() != ROOT / 'voxy-rust.toml' \
            or sys.argv[4:] != ['--minecraft-port', '25587'] \
            or Path(sys.argv[1]).name != 'voxy-rust-server':
        raise SystemExit('Refusing to launch anything except the Voxy_Testing Rust backend')
    sys.exit(run_limited(sys.argv[1:]))
