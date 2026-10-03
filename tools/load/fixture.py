#!/usr/bin/env python3
"""Deterministic saved Anvil terrain and independent wall-clock block edits."""
import argparse, array, json, os, random, struct, time, zlib
from pathlib import Path

BLOCKS = ['minecraft:air', 'minecraft:stone', 'minecraft:dirt',
          'minecraft:grass_block', 'minecraft:water', 'minecraft:gold_block']

def tag(kind, name, value):
    n = name.encode(); return bytes([kind]) + struct.pack('>H', len(n)) + n + value
def string(value):
    b = value.encode(); return struct.pack('>H', len(b)) + b
def compound(items): return b''.join(items) + b'\0'
def listing(kind, items): return bytes([kind]) + struct.pack('>i', len(items)) + b''.join(items)
def region(i): return ((i % 10) * 4 - 20, (i // 10) * 4 - 20)

def states(cx, cz, sy):
    out = bytearray(4096)
    for z in range(16):
        for x in range(16):
            wx, wz = cx*16+x, cz*16+z
            height = 22 + ((wx*17 ^ wz*31 ^ (wx//11)*47) % 21)
            for y in range(16):
                wy = sy*16+y
                out[x | z << 4 | y << 8] = (1 if wy < height-3 else 2 if wy < height else
                    3 if wy == height else 4 if wy < 27 else 0)
    return out

def chunk(cx, cz, edits):
    palette = []
    for name in BLOCKS:
        props = {'snowy':'false'} if name.endswith('grass_block') else {'level':'0'} if name.endswith('water') else {}
        palette.append(compound([tag(8, 'Name', string(name))] +
            ([tag(10, 'Properties', compound([tag(8,k,string(v)) for k,v in props.items()]))] if props else [])))
    sections = []
    for sy in range(4):
        data = states(cx,cz,sy)
        for index, value in edits.items():
            if index//4096 == sy: data[index%4096] = value
        words = array.array('Q')
        for off in range(0,4096,16):
            word = 0
            for j,v in enumerate(data[off:off+16]): word |= v << (4*j)
            words.append(word)
        if os.sys.byteorder == 'little': words.byteswap()
        sky = bytes([0xff])*2048 if sy>=2 else bytes(2048)
        sections.append(compound([
            tag(1,'Y',struct.pack('>b',sy)),
            tag(10,'block_states',compound([tag(9,'palette',listing(10,palette)),
                tag(12,'data',struct.pack('>i',len(words))+words.tobytes())])),
            tag(10,'biomes',compound([tag(9,'palette',listing(8,[string('minecraft:plains')]))])),
            tag(7,'SkyLight',struct.pack('>i',2048)+sky),
            tag(7,'BlockLight',struct.pack('>i',2048)+bytes(2048))]))
    nbt = tag(10,'',compound([tag(3,'DataVersion',struct.pack('>i',3955)),
        tag(3,'xPos',struct.pack('>i',cx)),tag(3,'zPos',struct.pack('>i',cz)),
        tag(8,'Status',string('minecraft:full')),tag(9,'sections',listing(10,sections))]))
    return zlib.compress(nbt,1)

def save(path, blobs, revision, stamps=None):
    output = bytearray(8192)
    for slot,blob in sorted(blobs.items()):
        sector = len(output)//4096; sectors = (len(blob)+5+4095)//4096
        struct.pack_into('>I',output,slot*4,(sector<<8)|sectors)
        struct.pack_into('>I',output,4096+slot*4,revision if stamps is None else stamps[slot])
        output += struct.pack('>I',len(blob)+1)+b'\x02'+blob
        output += bytes((sector+sectors)*4096-len(output))
    temporary = path.with_suffix('.mca.next')
    with temporary.open('wb') as f: f.write(output); f.flush(); os.fsync(f.fileno())
    os.replace(temporary,path)

def create(root):
    root.mkdir(parents=True,exist_ok=True)
    for i in range(100):
        rx,rz = region(i)
        blobs = {x+z*32:chunk(rx*32+x,rz*32+z,{}) for z in range(8) for x in range(8)}
        save(root/f'r.{rx}.{rz}.mca',blobs,1)
    print(json.dumps({'event':'fixture_ready','regions':100,'chunks':6400,'vertical_sections':4,
        'saved_bytes':sum(p.stat().st_size for p in root.glob('*.mca'))}),flush=True)

def mutate(root,duration,mode,report,save_every=1):
    rng = random.Random(244); changes = {}; blobs = {}; stamps = {}; dirty = set(); changed = set(); chunks = set(); regions = set()
    for i in range(100):
        rx,rz = region(i); raw = (root/f'r.{rx}.{rz}.mca').read_bytes(); b = {}
        for z in range(32):
            for x in range(32):
                slot=x+z*32; loc=struct.unpack_from('>I',raw,slot*4)[0]
                if loc==0:continue
                off=(loc>>8)*4096
                length=struct.unpack_from('>I',raw,off)[0]; b[slot]=raw[off+5:off+4+length]
        blobs[i]=b;stamps[i]={slot:struct.unpack_from('>I',raw,4096+slot*4)[0]for slot in b}
    patch=32 if len(blobs[0])==1024 else 8
    start=time.monotonic(); applied=persisted=0; batch=0; cpu=time.process_time(); saved_bytes=0; saves=0
    print(json.dumps({'event':'mutation_started','epoch':time.time(),'target_rate':300,'mode':mode}),flush=True)
    with Path(report).with_suffix('.jsonl').open('w') as log:
        while batch < int(duration*10):
            deadline=start+(batch+1)/10
            time.sleep(max(0,deadline-time.monotonic()))
            for _ in range(30):
                i = rng.randrange(100 if mode=='scattered' else 4)
                x,z=rng.randrange(patch*16),rng.randrange(patch*16); y=rng.randrange(10,20)
                cx,cz=x//16,z//16; slot=cx+cz*32; index=(x%16)|((z%16)<<4)|(y<<8)
                key=(i,slot)
                if key not in changes:
                    raw=bytearray(zlib.decompress(blobs[i][slot])); marker=b'\x0c\x00\x04data\x00\x00\x01\x00'
                    offsets=[]; cursor=0
                    while (cursor:=raw.find(marker,cursor))>=0:
                        offsets.append(cursor+len(marker)); cursor+=len(marker)+2048
                    assert len(offsets)==4
                    changes[key]=(raw,offsets)
                raw,offsets=changes[key]; local=index%4096
                offset=offsets[y//16]+(local//16)*8+7-(local%16)//2; shift=(local%2)*4
                old=(raw[offset]>>shift)&15; value=1 if old==5 else 5
                assert value!=old
                raw[offset]=(raw[offset]&~(15<<shift))|(value<<shift)
                dirty.add(key); changed.add((i,x,y,z)); chunks.add(key); regions.add(i); applied+=1
            batch+=1
            if batch%int(save_every*10)==0 or batch==int(duration*10):
                touched = {i for i,_ in dirty}
                for i,slot in dirty:
                    blobs[i][slot]=zlib.compress(changes[i,slot][0],1);stamps[i][slot]=batch+1
                for i in touched:
                    rx,rz=region(i); p=root/f'r.{rx}.{rz}.mca'; save(p,blobs[i],batch+1,stamps[i]); saved_bytes+=p.stat().st_size; saves+=1
                dirty.clear(); persisted=applied
                event={'elapsed':time.monotonic()-start,'schedule_elapsed':batch/10,'save_lag_seconds':time.monotonic()-start-batch/10,
                    'requested':batch*30,'persisted':persisted,'saved_bytes':saved_bytes}
                log.write(json.dumps(event)+'\n'); log.flush(); print(json.dumps(event),flush=True)
    result={'mode':mode,'seed':244,'target_rate':300,'requested':int(duration*10)*30,
        'achieved_changes':applied,'persisted_changes':persisted,'elapsed_seconds':time.monotonic()-start,
        'achieved_rate':applied/(time.monotonic()-start),'scheduled_input_rate':300,
        'last_edit_seconds':deadline-start,'distinct_blocks':len(changed),
        'distinct_chunks':len(chunks),'distinct_regions':len(regions),'save_cadence_seconds':save_every,
        'saved_bytes':saved_bytes,'region_saves':saves,'cpu_seconds':time.process_time()-cpu}
    Path(report).write_text(json.dumps(result,indent=2)+'\n'); print(json.dumps(result),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser(); p.add_argument('action',choices=['create','mutate']); p.add_argument('world',type=Path)
    p.add_argument('--seconds',type=float,default=60); p.add_argument('--mode',choices=['scattered','hotspot'],default='scattered')
    p.add_argument('--report',default='mutations.json');p.add_argument('--save-every',type=float,default=1); a=p.parse_args(); root=a.world/'region'
    create(root) if a.action=='create' else mutate(root,a.seconds,a.mode,a.report,a.save_every)
