"""One typed action on the connected real Testing client; no fake clients or test suite."""
import argparse,json,sys,time
from pathlib import Path
ROOT=Path('/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates')
sys.path.insert(0,str(ROOT))
from tools.voxy_testing_control import command
p=argparse.ArgumentParser();p.add_argument('run');p.add_argument('step',type=int);p.add_argument('action');p.add_argument('values',nargs='*');p.add_argument('--wait',type=int,default=35);p.add_argument('--clock',type=Path);a=p.parse_args()
assert 1<=a.wait<=55
clock_end = None
if a.clock:
 clock = json.loads(a.clock.read_text())
 clock_end = clock['monotonic_start'] + clock['limit_seconds']
 if a.action not in ('end', 'abort') and (clock.get('status') != 'active' or time.monotonic() + a.wait >= clock_end):
  raise SystemExit('No observation: insufficient time remains in the single live clock')
response = command(' '.join(['voxytest',a.action,a.run,str(a.step),*a.values])).strip()
print(response,flush=True)
# Rejected commands did not advance the server step; waiting for a result conceals this.
if any(message in response for message in ('still outstanding', 'step must', 'invalid ', 'unknown ', 'no active ', 'cannot ')):
 raise SystemExit(1)
file=ROOT.parent/'Voxy_Testing/logs/voxy-tests'/a.run/'events.jsonl'
deadline=time.monotonic()+a.wait
if clock_end is not None and a.action not in ('end','abort'): deadline=min(deadline,clock_end)
while time.monotonic()<deadline:
 for line in file.read_text().splitlines():
  try:row=json.loads(line);r=row.get('result',{})
  except json.JSONDecodeError:continue
  if r.get('stepId')==a.step and r.get('kind') not in ('TRACE_SAMPLE',):
   out=ROOT/'project_audit/live_client'/(a.run+'-step-'+str(a.step)+'.json')
   if not out.exists():out.write_text(json.dumps(row,indent=2)+'\n')
   print(json.dumps({k:r.get(k) for k in ['kind','stepId','failure','connectionEpoch']}),flush=True)
   sys.exit(0 if r.get('failure')=='NONE' else 1)
 time.sleep(.5)
print('Typed result not received before the observation timeout',flush=True);sys.exit(2)
