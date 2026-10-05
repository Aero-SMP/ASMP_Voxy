import hashlib,json,os,re,shutil,socket,subprocess,time,zipfile
from pathlib import Path
from datetime import datetime,timezone
ROOT=Path('/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates')
TESTING=ROOT.parent/'Voxy_Testing'
AUDIT=ROOT/'project_audit/deployment'
VERSION='0.2.257-beta'
for name in ('preflight257.json','runtime257.json','stop_server257.json','start_server257.json'):
 assert not (AUDIT/name).exists(), 'Refusing to overwrite existing257 receipt '+name
CLOCK=json.loads((AUDIT/'live257-clock.json').read_text())
def remaining(): return CLOCK['monotonic_start']+600-time.monotonic()
assert remaining()>180, 'Insufficient time for safe deployment/restoration'
ARTIFACTS=json.loads((AUDIT/'staged257.json').read_text())
assert len(ARTIFACTS)==3
assert Path(ARTIFACTS[0]['path']).name=='ASMP_voxy-'+VERSION+'+1.21.1-neoforge-debug.jar'
assert Path(ARTIFACTS[1]['path']).name=='voxy-server-'+VERSION+'+1.21.1-neoforge-debug.jar'
for artifact in ARTIFACTS[:2]:
 with zipfile.ZipFile(artifact['path']) as archive:
  assert 'Implementation-Version: '+VERSION+'\n' in archive.read('META-INF/MANIFEST.MF').decode().replace('\r\n','\n')
with zipfile.ZipFile(ARTIFACTS[1]['path']) as archive:
 assert hashlib.sha256(archive.read('native/linux-x86_64/voxy-rust-server')).hexdigest()==ARTIFACTS[2]['sha256']
def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def processes(heap=False):
 rows=[]
 for p in Path('/proc').iterdir():
  if not p.name.isdigit(): continue
  try:
   if p.joinpath('cwd').resolve(strict=True)!=TESTING: continue
   comm=p.joinpath('comm').read_text().strip()
   if comm not in ('java','voxy-rust-serve'): continue
   row=dict(pid=int(p.name),comm=comm,cwd=str(TESTING),start_ticks=p.joinpath('stat').read_text().rsplit(')',1)[1].split()[19])
   if comm=='java' and heap:
    flags=subprocess.run(['jcmd',str(row['pid']),'VM.flags'],check=True,capture_output=True,text=True).stdout
    row['heap_bytes']={name:int(re.search(r'-XX:'+name+r'=(\d+)',flags).group(1)) for name in ('InitialHeapSize','MaxHeapSize')}
   if comm=='voxy-rust-serve':
    row['executable_sha256']=digest(p/'exe')
    cg=Path('/sys/fs/cgroup')/p.joinpath('cgroup').read_text().strip().split('::')[-1].lstrip('/')
    row['cgroup_path']=str(cg)
    row['limits']={n:cg.joinpath(n).read_text().strip() for n in ('memory.max','memory.swap.max','memory.current','memory.events')}
   rows.append(row)
  except (OSError,RuntimeError): continue
 return rows
assert TESTING.resolve()==TESTING and not TESTING.is_symlink()
for a in ARTIFACTS: assert digest(Path(a['path']))==a['sha256']
old=processes(heap=True)
assert sorted(r['comm'] for r in old)==['java','voxy-rust-serve'],old
assert next(r for r in old if r['comm']=='java')['heap_bytes']=={'InitialHeapSize':1073741824,'MaxHeapSize':4294967296}
old_native=next(r for r in old if r['comm']=='voxy-rust-serve')
assert old_native['limits']['memory.max']=='999997440' and old_native['limits']['memory.swap.max']=='0'
other_mods={str(p):digest(p) for p in (TESTING/'mods').glob('*.jar') if not p.name.startswith('voxy-server')}
(AUDIT/'preflight257.json').write_text(json.dumps({'utc':datetime.now(timezone.utc).isoformat(),'processes':old,'other_mods':other_mods},indent=2)+'\n')
rollback=TESTING/'logs/cache-first-deployment'/('257-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
rollback.mkdir(parents=True)
for p in (TESTING/'mods').glob('voxy-server*.jar'): shutil.copy2(p,rollback/p.name)
for n in ('latest.log','client-upload/voxy-client-debug.log','client-upload/latest.log','client-upload/restart.log'):
 p=TESTING/'logs'/n
 if p.exists():
  target=rollback/n;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,target)
(AUDIT/'rollback257.path').write_text(str(rollback)+'\n')
(AUDIT/'current-rollback-path').write_text(str(rollback)+'\n')
conn=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);conn.settimeout(15);conn.connect('/run/user/1007/astolfo/astolfo.sock');stream=conn.makefile('rwb')
def send(o): stream.write((json.dumps(o)+'\n').encode());stream.flush()
def receive():
 line=stream.readline()
 if not line: raise RuntimeError('Astolfo closed')
 return json.loads(line)
send(dict(type='hello',protocol_version=6,client_name='voxy-cache-first-deploy',client_version='257'))
for _ in range(2): receive()
def action(kind,request):
 payload=dict(type=kind,request_id=request,server_id='voxy_testing')
 if kind=='start_server':payload['mode']='desired_online'
 send(payload)
 while True:
  row=receive()
  if row.get('request_id')==request and row.get('type')=='operation_result':
   (AUDIT/(kind+'257.json')).write_text(json.dumps(row,indent=2)+'\n');print(json.dumps(row),flush=True);return row
result=action('stop_server',25701)
deadline=min(time.monotonic()+60,CLOCK['monotonic_start']+420)
while processes():
 if time.monotonic()>deadline:raise RuntimeError('Testing processes did not stop')
 time.sleep(1)
source=Path(ARTIFACTS[1]['path']);target=TESTING/'mods'/source.name
pending=target.with_suffix('.jar.pending');shutil.copy2(source,pending);assert digest(pending)==ARTIFACTS[1]['sha256'];os.replace(pending,target)
for p in (TESTING/'mods').glob('voxy-server*.jar'):
 if p!=target:p.unlink()
result=action('start_server',25702)
deadline=min(time.monotonic()+90,CLOCK['monotonic_start']+420)
while True:
 rows=processes()
 if len(rows)==2 and 'Done (' in (TESTING/'logs/latest.log').read_text(errors='replace') and 'VOXY_READY udp_port=25787' in (TESTING/'logs/latest.log').read_text(errors='replace'):break
 if time.monotonic()>deadline:raise RuntimeError('Testing server did not reach MC+native ready')
 time.sleep(1)
rows=processes(heap=True)
native=next(r for r in rows if r['comm']=='voxy-rust-serve');assert native['executable_sha256']==ARTIFACTS[2]['sha256'];assert native['limits']['memory.max']=='999997440';assert native['limits']['memory.swap.max']=='0'
assert next(r for r in rows if r['comm']=='java')['heap_bytes']=={'InitialHeapSize':1073741824,'MaxHeapSize':4294967296}
assert other_mods=={str(p):digest(p) for p in (TESTING/'mods').glob('*.jar') if not p.name.startswith('voxy-server')}
feed=ROOT/'build/libs/debug-clients/MGengine';feed.mkdir(parents=True,exist_ok=True)
client=Path(ARTIFACTS[0]['path']);pending=feed/(client.name+'.pending');shutil.copy2(client,pending);assert digest(pending)==ARTIFACTS[0]['sha256'];os.replace(pending,feed/client.name)
receipt=dict(utc=datetime.now(timezone.utc).isoformat(),version=VERSION,processes=rows,artifacts=ARTIFACTS,rollback=str(rollback))
(AUDIT/'runtime257.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt),flush=True)
