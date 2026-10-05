"""One authorized PC action inside the continuous live window; preserves raw receipts."""
import base64,json,subprocess,sys,time
from datetime import datetime,timezone
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
clock=json.loads((ROOT/'project_audit/deployment/live257-clock.json').read_text())
action=sys.argv[1]
assert action in ('preflight','prepare','state','release','normal')
remaining=clock['monotonic_start']+600-time.monotonic()
assert remaining>15,'Live window expired'
script=ROOT/'project_audit/live_client'/('pc257-'+action+'.ps1')
encoded=base64.b64encode(script.read_text().encode('utf-16le')).decode()
connection=sys.argv[2] if len(sys.argv)>2 else 'primary'
result=subprocess.run(['bash',str(ROOT/'tools/pc_ssh_backup/connect.sh'),connection,
 'powershell.exe -NoProfile -NonInteractive -EncodedCommand '+encoded],capture_output=True,text=True,timeout=min(50,remaining-5))
receipt=ROOT/'project_audit/live_client'/('pc257-'+action+'-'+datetime.now(timezone.utc).strftime('%H%M%S')+'.json')
row=dict(action=action,utc=datetime.now(timezone.utc).isoformat(),elapsed=time.monotonic()-clock['monotonic_start'],exit=result.returncode,stdout=result.stdout,stderr=result.stderr)
try: row['data']=json.loads(result.stdout.strip().lstrip('\ufeff'))
except ValueError: pass
receipt.write_text(json.dumps(row,indent=2)+'\n')
summary={k:v for k,v in row.items() if k not in ('stdout','stderr','data')}
summary['receipt']=str(receipt)
if 'data' in row: summary['data']={k:v for k,v in row['data'].items() if k not in ('latest','files','helpers')}
else: summary['stdout']=result.stdout[-2000:];summary['stderr']=result.stderr[-2000:]
print(json.dumps(summary,indent=2))
sys.exit(result.returncode)
