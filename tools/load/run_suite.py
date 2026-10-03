#!/usr/bin/env python3
"""Run isolated, externally capped real-QUIC workloads; never controls Main."""
import argparse,hashlib,json,os,re,shutil,subprocess,threading,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
DRIVER=ROOT/'tools/load/target/release/voxy-load'
def run(cmd,**kw): return subprocess.run([str(x)for x in cmd],check=True,**kw)
def optional_read(path):
    try:return path.read_text()
    except OSError as e:return str(e)
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def properties(unit):
    p=subprocess.run(['systemctl','--user','show',unit,'-p','MainPID','-p','ControlGroup','-p','MemoryMax','-p','MemorySwapMax','-p','OOMPolicy','-p','Result','-p','MemoryPeak','-p','CPUUsageNSec','-p','ActiveState'],capture_output=True,text=True)
    return dict(line.split('=',1)for line in p.stdout.splitlines()if '='in line)
def main():
    p=argparse.ArgumentParser();p.add_argument('protocol',choices=['old','new']);p.add_argument('--name',required=True)
    p.add_argument('--full',action='store_true');p.add_argument('--cases',default='cold,warm,partial,cluster,slow,teleport,burst,changing,convergence,hotspot,convergence,offline')
    p.add_argument('--impaired',action='store_true');p.add_argument('--refresh-ms',type=int,default=1000);p.add_argument('--warm-from',type=Path);p.add_argument('--backend',type=Path,default=ROOT/'rust-server/target/release/voxy-rewrite-server');a=p.parse_args()
    out=ROOT/'project_audit/load_results'/a.name
    if out.exists():raise RuntimeError('Refusing to overwrite benchmark evidence')
    out.mkdir();shutil.copytree((a.warm_from/'world')if a.warm_from else(ROOT/('project_audit/load_results/fixture-full/world'if a.full else'project_audit/load_results/fixture-base/world')),out/'world')
    if a.warm_from:
        for name in('data','cache'):shutil.copytree(a.warm_from/name,out/name)
    unit=f'voxy-load-{a.name}';backend=DRIVER if a.protocol=='old'else a.backend
    command=[DRIVER,'old-server',out/'world',out/'data']if a.protocol=='old'else[backend,'--world',out/'world','--data',out/'data','--listen','127.0.0.1:0','--refresh-ms',a.refresh_ms]
    log=(out/'backend.log').open('w');server=subprocess.Popen(['systemd-run','--user','--unit='+unit,'--property=MemoryMax=1000000000','--property=MemorySwapMax=0','--property=OOMPolicy=kill','--property=KillMode=control-group','--working-directory='+str(ROOT),'--pipe','--wait',*[str(x)for x in command]],stdout=log,stderr=subprocess.STDOUT)
    metadata={'protocol':a.protocol,'unit':unit,'started_epoch':time.time(),'backend_sha256':sha(backend),'driver_sha256':sha(DRIVER),'fixture_generator_sha256':sha(ROOT/'tools/load/fixture.py'),
        'fixture_regions':100,'chunks_per_region':1024 if a.full else 64,'saved_vertical_sections':4,'view_width_blocks':512 if a.full else 128,'coarse_level':4 if a.full else 2,'detail_level':0,'detail_sections_per_client':512 if a.full else 32,'available_lod_keys_per_client':597 if a.full else 33,
        'virtual_routes':'seed244; moving prioritycamera within128x128patch; teleport17regions/5seconds',
        'network':'loopbackQUIC,CA-trustedpinnedself-signedcertificate','os_cache':'uncontrolled shared host cache; derived cold then warm','cases':[],'refresh_ms':a.refresh_ms if a.protocol=='new' else 2000}
    stop_monitor=threading.Event();monitor_thread=None;proxy=None
    try:
        ready=None;deadline=time.monotonic()+60
        while time.monotonic()<deadline:
            text=(out/'backend.log').read_text();ready=re.search(r'VOXY_READY udp_port=(\d+)',text)
            if ready:break
            if server.poll()is not None:raise RuntimeError('Backend exited before readiness')
            time.sleep(.1)
        if not ready:raise RuntimeError('Backend readiness timeout')
        address='127.0.0.1:'+ready[1];cert=out/'data/quic'/('certificate.der'if a.protocol=='old'else'server-cert.der')
        metadata['certificate_sha256']=sha(cert);metadata['guard']=properties(unit)
        if a.impaired:
            proxy=subprocess.Popen(['python3',str(ROOT/'tools/load/impair.py'),address,'--ports',str(out/'proxy-ports.json'),'--report',str(out/'impairment.json')],stdout=(out/'impairment.log').open('w'),stderr=subprocess.STDOUT)
            deadline=time.monotonic()+10
            while not(out/'proxy-ports.json').exists():
                if proxy.poll()is not None or time.monotonic()>deadline:raise RuntimeError('Impairment proxy startup')
                time.sleep(.05)
            address='@'+str(out/'proxy-ports.json')
            metadata['impairment']={'loss_each_direction':'.50..90seededperclient','mbps_each_direction':3,'delay_each_direction_ms':500,'minimum_rtt_ms':1000,'including_handshake':True}

        cg=Path('/sys/fs/cgroup')/metadata['guard']['ControlGroup'].lstrip('/');pid=metadata['guard']['MainPID']
        def monitor():
            with(out/'resources.jsonl').open('w')as f:
                while not stop_monitor.is_set():
                    try:
                        row={'epoch':time.time(),'memory_current':int((cg/'memory.current').read_text()),'memory_peak':int((cg/'memory.peak').read_text()),
                            'memory_max':(cg/'memory.max').read_text().strip(),'swap_max':(cg/'memory.swap.max').read_text().strip(),
                            'cpu':(cg/'cpu.stat').read_text(),'io':(cg/'io.stat').read_text()if(cg/'io.stat').exists()else'not enabled for user slice','events':(cg/'memory.events').read_text(),
                            'status':Path('/proc')/pid/'status'}
                        row['status']=optional_read(row['status']);row['process_io']=optional_read(Path('/proc')/pid/'io')
                        f.write(json.dumps(row)+'\n');f.flush()
                    except(FileNotFoundError,PermissionError)as e:
                        f.write(json.dumps({'epoch':time.time(),'observer_error':str(e)})+'\n');break
                    stop_monitor.wait(.5)
        monitor_thread=threading.Thread(target=monitor,daemon=True);monitor_thread.start()
        for number,case in enumerate(a.cases.split(',')):
            if server.poll()is not None:raise RuntimeError('Backend stopped before '+case)
            seconds=65 if case in('changing','cached-changing','hotspot')else 25 if case in('slow','teleport','burst','convergence')else 10
            if case=='cold':seconds=25
            if case=='offline':seconds=2
            if case=='convergence':run([DRIVER,'expected',out/'world',out],stdout=(out/f'{number:02}-{case}-expected.log').open('w'),stderr=subprocess.STDOUT)
            file=out/f'{number:02}-{case}.json';driver_log=out/f'{number:02}-{case}.log'
            driver_sha=sha(DRIVER);logf=driver_log.open('w');client=subprocess.Popen([str(x)for x in[DRIVER,a.protocol,case,seconds,address,cert,out/'world',out/'cache',100,file]],stdout=logf,stderr=subprocess.STDOUT)
            mutation=None
            if case in('changing','cached-changing','hotspot'):
                deadline=time.monotonic()+(150 if a.impaired else 30)
                while time.monotonic()<deadline:
                    if 'virtual_clients_ready'in driver_log.read_text():break
                    if client.poll()is not None:raise RuntimeError('Driver exited before simultaneous clients')
                    time.sleep(.05)
                else:raise RuntimeError('100clients did not reach start barrier')
                mode='hotspot'if case=='hotspot'else'scattered'
                mutation=subprocess.Popen(['python3',str(ROOT/'tools/load/fixture.py'),'mutate',str(out/'world'),'--seconds','60','--mode',mode,'--report',str(out/f'{number:02}-{case}-mutations.json'),'--save-every',str(5 if a.full else 1)],stdout=(out/f'{number:02}-{case}-mutations.log').open('w'),stderr=subprocess.STDOUT)
            try:code=client.wait(timeout=seconds+(300 if a.impaired else 150))
            except subprocess.TimeoutExpired:client.kill();code=client.wait();raise RuntimeError('Driver deadline '+case)
            logf.close()
            if mutation is not None:mutation.wait(timeout=120)
            props=properties(unit);record={'driver_sha256':driver_sha,'case':case,'report':str(file.relative_to(ROOT)),'driver_exit':code,'backend':props}
            if file.exists():
                r=json.loads(file.read_text());record.update({k:r[k]for k in('all_connected','error_count','sections','payload_bytes','latency_cycle_ms')})
                record['coarse_clients']=sum(s['first_coarse_ms']is not None for s in r['per_client']);record['detail_clients']=sum(s['first_detail_ms']is not None for s in r['per_client'])
                record['incomplete_coverage_cycles']=sum(s['incomplete_cycles']for s in r['per_client'])
                record['local_decoded_sections']=sum(s['local_decoded_sections']for s in r['per_client']);record['connection_attempts']=sum(s['connection_attempts']for s in r['per_client']);record['connected_clients']=sum(s['connected']for s in r['per_client']);record['unfinished_cycles']=sum(s['unfinished_cycles']for s in r['per_client'])
                record['final_expected']=sum(s['final_expected']for s in r['per_client']);record['final_matching']=sum(s['final_matching']for s in r['per_client'])
            metadata['cases'].append(record);(out/'run.json').write_text(json.dumps(metadata,indent=2)+'\n');print(json.dumps(record),flush=True)
            if server.poll()is not None:raise RuntimeError('External guard/backend termination')
    except Exception as e:
        metadata['failure']=str(e);print('FAILED '+str(e),flush=True)
    finally:
        metadata['before_stop']=properties(unit);stop_monitor.set()
        if proxy is not None:
            proxy.terminate()
            try:proxy.wait(timeout=5)
            except subprocess.TimeoutExpired:proxy.kill();proxy.wait()
        if monitor_thread is not None:monitor_thread.join(timeout=2)
        subprocess.run(['systemctl','--user','stop',unit],capture_output=True)
        try:server.wait(timeout=10)
        except subprocess.TimeoutExpired:server.kill();server.wait()
        log.close();metadata['finished_epoch']=time.time();metadata['after_stop']=properties(unit)
        (out/'run.json').write_text(json.dumps(metadata,indent=2)+'\n')
    return 1 if 'failure'in metadata else 0
if __name__=='__main__':raise SystemExit(main())
