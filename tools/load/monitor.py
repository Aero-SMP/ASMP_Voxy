#!/usr/bin/env python3
"""Observe only the isolated backend cgroup and report raw resource counters."""
import argparse,json,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('cgroup');p.add_argument('pid',type=int);p.add_argument('seconds',type=float);p.add_argument('output',type=Path);a=p.parse_args()
root=Path('/sys/fs/cgroup')/a.cgroup.lstrip('/');start=time.monotonic()
with a.output.open('w') as f:
    while time.monotonic()-start<a.seconds:
        try:
            item={'elapsed':time.monotonic()-start,'memory_current':int((root/'memory.current').read_text()),'memory_peak':int((root/'memory.peak').read_text()),
                'memory_max':(root/'memory.max').read_text().strip(),'swap_max':(root/'memory.swap.max').read_text().strip(),
                'cpu':(root/'cpu.stat').read_text(),'io':(root/'io.stat').read_text(),
                'memory_events':(root/'memory.events').read_text(),'process_io':Path(f'/proc/{a.pid}/io').read_text(),
                'process_status':Path(f'/proc/{a.pid}/status').read_text()}
            f.write(json.dumps(item)+'\n');f.flush()
        except FileNotFoundError:
            f.write(json.dumps({'elapsed':time.monotonic()-start,'backend_exited':True})+'\n');break
        time.sleep(1)
