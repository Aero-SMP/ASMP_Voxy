"""Measure one isolated test process, excluding Cargo compilation."""
import resource
import subprocess
import sys
import time

start = time.monotonic()
result = subprocess.run(sys.argv[1:])
usage = resource.getrusage(resource.RUSAGE_CHILDREN)
print(f"wall_s={time.monotonic() - start:.6f} user_s={usage.ru_utime:.6f} "
      f"system_s={usage.ru_stime:.6f} max_rss_kib={usage.ru_maxrss}", flush=True)
sys.exit(result.returncode)
