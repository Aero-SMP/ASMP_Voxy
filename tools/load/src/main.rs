use anyhow::{bail, Context, Result};
use quinn::{Connection, Endpoint, RecvStream, SendStream};
use rustls::pki_types::CertificateDer;
use serde::Serialize;
use sha2::{Digest,Sha256};
use std::{collections::{BTreeMap,HashMap},path::{Path,PathBuf},sync::{Arc,RwLock,OnceLock,atomic::{AtomicUsize,Ordering}},time::{Duration,Instant}};
use tokio::io::{AsyncReadExt,AsyncWriteExt};
use voxy_rust_server::{anvil::AnvilWorld,catalog::Catalog,key::SectionKey,regional::{RegionIndex,RegionalService,SectionFrame,wire::*},registry::Registry,server::{self,ServerState}};

const DIM:&str="minecraft:overworld";
type Key=(u8,i32,i32,i32);
#[derive(Clone,Default)] struct Cached { wire:[u8;32], cells:[u8;32] }
#[derive(Default,Serialize)] struct Stats {
    client:usize, connected_ms:f64, connected:bool, load_elapsed_seconds:f64, local_decoded_sections:u64, local_decode_ms:f64, connection_attempts:u64, network_rtt_ms:Option<f64>, unfinished_cycles:u64, requests:u64, sections:u64, payload_bytes:u64, local_hits:u64,
    unavailable:u64, unchanged:u64, changed:u64, failures:Vec<String>, latency_ms:Vec<f64>,
    first_coarse_ms:Option<f64>, first_detail_ms:Option<f64>, coverage_complete_cycles:u64,
    incomplete_cycles:u64, cache_entries:usize, final_expected:usize, final_matching:usize,
}
#[derive(Clone)] struct Settings { protocol:String, case:String, seconds:f64, start:Instant, load_start:Arc<OnceLock<Instant>>, world:PathBuf, cache:PathBuf, full:bool, impaired:bool, connected_now:Arc<AtomicUsize>, connected_peak:Arc<AtomicUsize> }
struct Peer { conn:Connection, control_send:SendStream, control_recv:RecvStream,
    lane:Option<(SendStream,RecvStream)>, catalog:Names, catalog_hash:[u8;32], slow:bool, world:[u8;16], cache:PathBuf, catalog_raw:Vec<u8>, epoch:u64 }
#[derive(Default,Clone)] struct Names { blocks:Vec<String>, biomes:Vec<String> }

fn region(i:usize)->(i32,i32) { (((i%10)as i32)*4-20, ((i/10)as i32)*4-20) }
fn keys(rx:i32,rz:i32,elapsed:f64,full:bool)->Vec<Key> {
    let side=if full {16}else{4};
    let mut detail=Vec::new();
    for y in 0..2 { for z in 0..side { for x in 0..side { detail.push((0,rx*16+x,y,rz*16+z)); } } }
    let camera=((elapsed*0.8).sin()*1.5+1.5,(elapsed*0.3).cos()*1.5+1.5);
    detail.sort_by_key(|k| (((k.1-rx*16)as f64-camera.0).powi(2)*100.0+((k.3-rz*16)as f64-camera.1).powi(2)*100.0) as i32);
    let mut out=Vec::new();
    if full {for level in (1..=4).rev() {let count=16>>level;for z in 0..count {for x in 0..count {out.push((level,rx*count+x,0,rz*count+z));}}}}
    else {out.push((2,rx*4,0,rz*4));}out.extend(detail);out
}
fn state_id(name:&str)->Result<u8> {
    Ok(match name {
        "minecraft:air"=>0,"minecraft:stone"=>1,"minecraft:dirt"=>2,
        "minecraft:grass_block[snowy=false]"=>3,"minecraft:water[level=0]"=>4,"minecraft:gold_block"=>5,
        _=>bail!("unexpected fixture state {name}"),
    })
}
fn digest_cells(cells:&[voxy_rust_server::lod::Cell],names:&Names,old_light:bool)->Result<[u8;32]> {
    let ids=names.blocks.iter().map(|n|state_id(n)).collect::<Result<Vec<_>>>()?;
    let mut bytes=Vec::with_capacity(cells.len()*3);
    for c in cells { let state=*ids.get(c.block as usize).context("catalog block index")?;
        let biome=names.biomes.get(c.biome as usize).context("catalog biome index")?;
        if biome!="minecraft:plains" { bail!("unexpected biome {biome}"); }
        bytes.extend([state,0,if old_light {c.light.rotate_left(4)} else {c.light}]); }
    Ok(*blake3::hash(&bytes).as_bytes())
}
fn parse_catalog(b:&[u8])->Result<Names> {
    if b.len()<16 || &b[..8]!=b"VXRCAT01" { bail!("bad new catalog"); }
    let mut p=8; let mut lists=Vec::new();
    for _ in 0..2 { let count=u32::from_le_bytes(b[p..p+4].try_into()?) as usize; p+=4; let mut out=Vec::new();
        for _ in 0..count { let n=u16::from_le_bytes(b[p..p+2].try_into()?)as usize; p+=2;
            out.push(std::str::from_utf8(b.get(p..p+n).context("catalog truncated")?)?.to_string()); p+=n; } lists.push(out); }
    if p!=b.len() { bail!("trailing catalog"); } Ok(Names{blocks:lists.remove(0),biomes:lists.remove(0)})
}
fn new_cells(frame:&[u8])->Result<(Vec<voxy_rust_server::lod::Cell>,[u8;32])> {
    if frame.len()<81 || &frame[..8]!=b"VXRSEC01" { bail!("invalid section frame"); }
    let len=u32::from_le_bytes(frame[73..77].try_into()?)as usize;
    let compressed=u32::from_le_bytes(frame[77..81].try_into()?)as usize;
    if frame.len()!=81+compressed || len>2+32768*11 { bail!("invalid frame lengths"); }
    if Sha256::digest(&frame[81..]).as_slice()!=&frame[40..72] { bail!("compressed checksum mismatch"); }
    let b=zstd::bulk::decompress(&frame[81..],len)?; if b.len()!=len {bail!("canonical length");}
    let n=u16::from_le_bytes(b[..2].try_into()?)as usize;
    if n==0 || n>32768 { bail!("canonical palette shape"); }
    let bits=(usize::BITS-(n-1).leading_zeros()).max(1) as usize;let per_word=64/bits;
    let expected=2+n*9+32768usize.div_ceil(per_word)*8;
    if b.len()!=expected {bail!("canonical packed length");}
    let mut palette=Vec::new();
    for c in b[2..2+n*9].chunks_exact(9) { palette.push(voxy_rust_server::lod::Cell{block:u32::from_le_bytes(c[..4].try_into()?),biome:u32::from_le_bytes(c[4..8].try_into()?),light:c[8]}); }
    let mut cells=Vec::with_capacity(32768);
    for raw in b[2+n*9..].chunks_exact(8) {let word=u64::from_le_bytes(raw.try_into()?);
        for offset in 0..per_word {if cells.len()==32768 {break;}let index=((word>>(offset*bits))&((1<<bits)-1))as usize;
            cells.push(*palette.get(index).context("palette index")?);}
    }
    Ok((cells,frame[8..40].try_into()?))
}
async fn record(send:&mut SendStream,m:&ControlMessage)->Result<()> {send.write_all(&encode_control_record(m)?).await?;Ok(())}
fn hex(bytes:&[u8])->String {bytes.iter().map(|b|format!("{b:02x}")).collect()}
fn payload_path(dir:&Path,key:Key)->PathBuf {dir.join(format!("{}.{}.{}.{}.frame",key.0,key.1,key.2,key.3))}
fn write_atomic(path:&Path,b:&[u8])->Result<()> {let next=path.with_extension("next");std::fs::write(&next,b)?;std::fs::rename(next,path)?;Ok(())}
impl Peer {
    fn persist(&self,key:Key,kind:u8,canonical:u32,bytes:&[u8])->Result<()> {
        std::fs::create_dir_all(&self.cache)?;let cat=self.cache.join(format!("{}.cat",hex(&self.catalog_hash)));
        if !cat.exists() {write_atomic(&cat,&self.catalog_raw)?;}
        let mut data=vec![kind];data.extend(canonical.to_le_bytes());data.extend(self.catalog_hash);data.extend(bytes);
        write_atomic(&payload_path(&self.cache,key),&data)
    }
    async fn open(endpoint:&Endpoint,address:std::net::SocketAddr,protocol:&str,cache:PathBuf)->Result<Self> {
        let conn=endpoint.connect(address,"voxy.local")?.await?;
        let (mut send,mut recv)=conn.open_bi().await?;
        if protocol=="old" {
            send.write_u8(STREAM_CONTROL).await?;record(&mut send,&ControlMessage::Hello{dimension:DIM.into()}).await?;
            if !matches!(read_control(&mut recv).await?,Some(ControlMessage::ServerHello{..})) { bail!("old hello"); }
            record(&mut send,&ControlMessage::CatalogRequest).await?;
            let (names,catalog_hash,catalog_raw)=loop {match read_control(&mut recv).await?.context("catalog eof")? {
                ControlMessage::Catalog{canonical,fingerprint}=>{let c=Catalog::decode(&canonical)?; break (Names{blocks:c.blocks.into_iter().map(|b|b.canonical).collect(),biomes:c.biomes},fingerprint,canonical);},
                ControlMessage::RegionChanged{..}=>{},m=>bail!("unexpected catalog {m:?}"),}};
            let(mut ls,lr)=conn.open_bi().await?;ls.write_all(&[STREAM_SECTION_LANE,0]).await?;
            Ok(Self{conn,control_send:send,control_recv:recv,lane:Some((ls,lr)),catalog:names,catalog_hash,slow:false,world:[0;16],cache,catalog_raw,epoch:0})
        } else {
            send.write_u16_le(DIM.len() as u16).await?;send.write_all(DIM.as_bytes()).await?;
            let mut world=[0;16];recv.read_exact(&mut world).await?;
            Ok(Self{conn,control_send:send,control_recv:recv,lane:None,catalog:Names::default(),catalog_hash:[0;32],slow:false,world,cache,catalog_raw:Vec::new(),epoch:0})
        }
    }
    async fn old_cycle(&mut self,rx:i32,rz:i32,wanted:&[Key],cache:&mut HashMap<Key,Cached>,s:&mut Stats)->Result<()> {
        record(&mut self.control_send,&ControlMessage::RegionRequest{region_x:rx,region_z:rz}).await?;
        let (index,catalog_hash)=loop {match read_control(&mut self.control_recv).await?.context("index eof")? {
            ControlMessage::Region{region_x,region_z,compressed,catalog_fingerprint,..} if (region_x,region_z)==(rx,rz)=>break (RegionIndex::decode(&zstd::bulk::decompress(&compressed,4*1024*1024)?)?,catalog_fingerprint),
            ControlMessage::RegionUnavailable{region_x,region_z,..} if (region_x,region_z)==(rx,rz)=>{s.unavailable+=wanted.len() as u64;return Ok(());},
            ControlMessage::RegionChanged{..}=>{},m=>bail!("unexpected index {m:?}"),}};
        if self.catalog_hash!=catalog_hash {
            record(&mut self.control_send,&ControlMessage::CatalogRequest).await?;
            loop {match read_control(&mut self.control_recv).await?.context("catalog eof")? {
                ControlMessage::Catalog{canonical,fingerprint}=>{let c=Catalog::decode(&canonical)?; self.catalog=Names{blocks:c.blocks.into_iter().map(|b|b.canonical).collect(),biomes:c.biomes};self.catalog_hash=fingerprint;self.catalog_raw=canonical;break;},
                ControlMessage::RegionChanged{..}=>{},m=>bail!("unexpected catalog {m:?}"),}}
        }
        let mut selected=Vec::new();
        for &(level,x,y,z) in wanted {
            let ordinal=index.layout.index(rx,rz,voxy_rust_server::regional::SectionCoordinate{level,x,y,z})?;
            let e=index.entries[ordinal];if !e.is_present() {s.unavailable+=1;continue;}
            if e.is_empty() {cache.insert((level,x,y,z),Cached::default());s.local_hits+=1;continue;}
            let mut hash=[0;32];hash[..16].copy_from_slice(&e.fingerprint);
            if cache.get(&(level,x,y,z)).is_some_and(|c|c.wire==hash) {s.local_hits+=1;continue;}
            selected.push(((level,x,y,z),e,hash,ordinal as u32));
        }
        if selected.is_empty() {return Ok(());}
        for selected in selected.chunks(MAX_SECTION_REQUESTS) {
        self.epoch+=1;let req=SectionRequestBatch{epoch:self.epoch,region_x:rx,region_z:rz,generation:index.generation,ordinals:selected.iter().map(|s|s.3).collect()};
        write_request_batch(&mut self.lane.as_mut().unwrap().0,&req).await?;s.requests+=selected.len()as u64;
        let mut count=0;
        while count<selected.len() {
            let (_,recv)=self.lane.as_mut().unwrap();
            let length=recv.read_u32_le().await? as usize; if length>64*1024*1024 {bail!("reply too large");}
            let mut b=vec![0;length];if self.slow {for chunk in b.chunks_mut(1024) {tokio::time::sleep(Duration::from_millis(50)).await;recv.read_exact(chunk).await?;}}else{recv.read_exact(&mut b).await?;}
            if b.len()<12 {bail!("short old reply");}let begin=u16::from_le_bytes(b[8..10].try_into()?)as usize;let n=u16::from_le_bytes(b[10..12].try_into()?)as usize;
            let meta=selected.get(begin..begin+n).context("batch range")?;
            let reply=SectionReplyBatch::decode(&b,&meta.iter().map(|m|m.1.compressed_length).collect::<Vec<_>>())?;
            for (r,(key,e,hash,_)) in reply.replies.into_iter().zip(meta) {
                if r.status!=SectionReplyStatus::Data {s.unavailable+=1;continue;}
                if voxy_rust_server::crc::crc32c(&r.compressed)!=e.compressed_crc {bail!("old crc mismatch");}
                let canonical=zstd::bulk::decompress(&r.compressed,e.canonical_length as usize)?;
                if blake3::hash(&canonical).as_bytes()[..16]!=e.fingerprint {bail!("old section fingerprint");}
                let cells=SectionFrame::decode(&canonical)?.cells; let digest=digest_cells(&cells,&self.catalog,true)?;
                if cache.contains_key(key) {s.changed+=1;} cache.insert(*key,Cached{wire:*hash,cells:digest});
                self.persist(*key,0,e.canonical_length,&r.compressed)?;
                s.sections+=1;s.payload_bytes+=r.compressed.len()as u64;
            }count+=n;
        }}Ok(())
    }
    async fn new_cycle(&mut self,wanted:&[Key],cache:&mut HashMap<Key,Cached>,s:&mut Stats)->Result<()> {
        for &(level,x,y,z) in wanted {
            let old=cache.get(&(level,x,y,z)).cloned().unwrap_or_default();
            let send=&mut self.control_send;send.write_u8(0).await?;send.write_u8(level).await?;
            send.write_i32_le(x).await?;send.write_i32_le(y).await?;send.write_i32_le(z).await?;send.write_all(&old.wire).await?;s.requests+=1;
        }
        for &key in wanted {match self.control_recv.read_u8().await? {
            0=>{s.unavailable+=1;},1=>{s.unchanged+=1;},2=>{
                let n=self.control_recv.read_u32_le().await? as usize;if n>4*1024*1024 {bail!("new frame too large");}
                let mut frame=vec![0;n];if self.slow {for chunk in frame.chunks_mut(1024) {tokio::time::sleep(Duration::from_millis(50)).await;self.control_recv.read_exact(chunk).await?;}}else{self.control_recv.read_exact(&mut frame).await?;}
                let (cells,cat)=new_cells(&frame)?;
                if self.catalog_hash!=cat {
                    if self.lane.is_none() {let(mut send,mut recv)=self.conn.open_bi().await?;
                        send.write_u16_le(DIM.len()as u16).await?;send.write_all(DIM.as_bytes()).await?;
                        let mut world=[0;16];recv.read_exact(&mut world).await?;if world!=self.world {bail!("catalog stream world mismatch");}
                        self.lane=Some((send,recv));
                    }
                    let(send,recv)=self.lane.as_mut().unwrap();send.write_u8(1).await?;send.write_all(&cat).await?;
                    let n=recv.read_u32_le().await? as usize;if n>64*1024*1024 {bail!("catalog too large");}
                    let mut b=vec![0;n];recv.read_exact(&mut b).await?;
                    if Sha256::digest(&b).as_slice()!=cat {bail!("catalog checksum");}
                    self.catalog=parse_catalog(&b)?;self.catalog_hash=cat;self.catalog_raw=b;
                }
                let digest=digest_cells(&cells,&self.catalog,false)?;
                if cache.contains_key(&key) {s.changed+=1;}cache.insert(key,Cached{wire:Sha256::digest(&frame).into(),cells:digest});
                self.persist(key,1,0,&frame)?;s.sections+=1;s.payload_bytes+=frame.len()as u64;
            },status=>bail!("unexpected new status {status}"),
        }}
        Ok(())
    }
}

fn cache_path(root:&Path,id:usize)->PathBuf {root.join(format!("client-{id}.json"))}
fn load_cache(root:&Path,id:usize)->HashMap<Key,Cached> {
    let Ok(b)=std::fs::read(cache_path(root,id)) else{return HashMap::new();};
    let items:Vec<(Key,[u8;32],[u8;32])>=serde_json::from_slice(&b).unwrap_or_default();
    items.into_iter().map(|(k,wire,cells)|(k,Cached{wire,cells})).collect()
}
fn save_cache(root:&Path,id:usize,cache:&HashMap<Key,Cached>)->Result<()> {
    std::fs::create_dir_all(root)?;let items=cache.iter().map(|(k,c)|(*k,c.wire,c.cells)).collect::<Vec<_>>();
    write_atomic(&cache_path(root,id),&serde_json::to_vec(&items)?)?;Ok(())
}
async fn virtual_client(id:usize,endpoint:Endpoint,address:std::net::SocketAddr,opts:Settings,barrier:Arc<tokio::sync::Barrier>)->Stats {
    let mut s=Stats{client:id,..Stats::default()};let mut cache=if ["warm","partial","offline","convergence","cached-changing"].contains(&opts.case.as_str()) {load_cache(&opts.cache,id)} else {HashMap::new()};
    if opts.case=="partial" {cache.retain(|k,_|k.0>0 || k.1%2==0);}
    let initial=keys(region(id%100).0,region(id%100).1,0.,opts.full);
    if cache.contains_key(&initial[0]) {s.first_coarse_ms=Some(0.);}
    if initial.iter().all(|k|cache.contains_key(k)) {s.first_detail_ms=Some(0.);}
    let local_start=Instant::now();s.first_coarse_ms=None;s.first_detail_ms=None;let payloads=opts.cache.join(format!("client-{id}-payloads"));
    if ["warm","partial","offline","convergence","cached-changing"].contains(&opts.case.as_str()) {
    for &key in &initial {if opts.case=="partial"&&key.0==0&&key.1%2!=0 {continue;}
        let validation=(||->Result<Cached> {let data=std::fs::read(payload_path(&payloads,key))?;
            if data.len()<37 {bail!("cache header");}let cat=std::fs::read(payloads.join(format!("{}.cat",hex(&data[5..37]))))?;
            let (cells,names,old,wire)=if data[0]==0 {if blake3::hash(&cat).as_bytes()!=&data[5..37] {bail!("cached catalog checksum");}
                let catalog=Catalog::decode(&cat)?;let names=Names{blocks:catalog.blocks.into_iter().map(|b|b.canonical).collect(),biomes:catalog.biomes};
                let canonical=zstd::bulk::decompress(&data[37..],u32::from_le_bytes(data[1..5].try_into()?)as usize)?;
                let mut wire=[0;32];wire[..16].copy_from_slice(&blake3::hash(&canonical).as_bytes()[..16]);
                (SectionFrame::decode(&canonical)?.cells,names,true,wire)
            }else {if Sha256::digest(&cat).as_slice()!=&data[5..37] {bail!("cached catalog checksum");}let(cells,hash)=new_cells(&data[37..])?;
                if hash!=data[5..37] {bail!("cached frame identity");}(cells,parse_catalog(&cat)?,false,Sha256::digest(&data[37..]).into())};
            Ok(Cached{wire,cells:digest_cells(&cells,&names,old)?})})();
        match validation {Ok(c)=>{cache.insert(key,c);s.local_decoded_sections+=1;if key==initial[0]{s.first_coarse_ms=Some(local_start.elapsed().as_secs_f64()*1000.);}},_=>{cache.remove(&key);}}
    }}
    s.local_decode_ms=local_start.elapsed().as_secs_f64()*1000.;
    if !cache.contains_key(&initial[0]){s.first_coarse_ms=None;}
    if initial.iter().all(|k|cache.contains_key(k)){s.first_detail_ms=Some(s.local_decode_ms);}else{s.first_detail_ms=None;}
    let mut peer=None;
    if !opts.impaired && opts.case!="offline" {
        s.connection_attempts+=1;match tokio::time::timeout(Duration::from_secs(120),Peer::open(&endpoint,address,&opts.protocol,payloads.clone())).await {
            Ok(Ok(p))=>{let count=opts.connected_now.fetch_add(1,Ordering::Relaxed)+1;opts.connected_peak.fetch_max(count,Ordering::Relaxed);s.connected=true;s.connected_ms=opts.start.elapsed().as_secs_f64()*1000.;s.network_rtt_ms=Some(p.conn.rtt().as_secs_f64()*1000.);peer=Some(p);},
            Ok(Err(e))=>s.failures.push(e.to_string()),Err(e)=>s.failures.push(format!("connect deadline: {e}")),
        }
    }
    let leader=barrier.wait().await;
    let load_start=*opts.load_start.get_or_init(Instant::now);
    if leader.is_leader(){println!("virtual_clients_ready clients={} offline={} persistent_actors={}",Arc::strong_count(&opts.load_start)-1,opts.case=="offline",opts.impaired);}
    let mut connecting:Option<tokio::task::JoinHandle<Result<Peer>>>=None;
    let mut retry_at=Instant::now();
    while load_start.elapsed().as_secs_f64()<opts.seconds {
        if opts.impaired && peer.is_none() {
            if connecting.as_ref().is_some_and(|t|t.is_finished()) {
                match connecting.take().unwrap().await {
                    Ok(Ok(p))=>{let count=opts.connected_now.fetch_add(1,Ordering::Relaxed)+1;opts.connected_peak.fetch_max(count,Ordering::Relaxed);s.connected=true;s.connected_ms=opts.start.elapsed().as_secs_f64()*1000.;s.network_rtt_ms=Some(p.conn.rtt().as_secs_f64()*1000.);peer=Some(p);},
                    Ok(Err(e))=>s.failures.push(format!("connect: {e:#}")),Err(e)=>s.failures.push(e.to_string()),
                }retry_at=Instant::now()+Duration::from_secs(1);
            }
            if peer.is_none() && connecting.is_none() && Instant::now()>=retry_at {
                s.connection_attempts+=1;let endpoint=endpoint.clone();let protocol=opts.protocol.clone();let payloads=payloads.clone();
                connecting=Some(tokio::spawn(async move {tokio::time::timeout(Duration::from_secs(120),Peer::open(&endpoint,address,&protocol,payloads)).await.context("connect deadline")?}));
            }
        }
        if let Some(p)=peer.as_mut(){p.slow=opts.case=="slow"&&id%10==0;}
        let elapsed=load_start.elapsed().as_secs_f64();let ri=if opts.case=="cluster" {0} else if opts.case=="teleport" || opts.case=="burst" {(id+(elapsed/5.)as usize*17)%100} else {id%100};
        let(rx,rz)=region(ri);let wanted=keys(rx,rz,elapsed,opts.full);let before=Instant::now();
        let online = if let Some(p)=peer.as_mut() {let operation=async {if opts.protocol=="old" {p.old_cycle(rx,rz,&wanted,&mut cache,&mut s).await} else {p.new_cycle(&wanted,&mut cache,&mut s).await}};
            let result=match tokio::time::timeout_at(tokio::time::Instant::from_std(load_start+Duration::from_secs_f64(opts.seconds)),operation).await {
                Ok(r)=>r,Err(_)=>{s.unfinished_cycles+=1;break;}};
            if let Err(e)=result {s.failures.push(format!("{e:#}"));if opts.impaired {peer.take();opts.connected_now.fetch_sub(1,Ordering::Relaxed);retry_at=Instant::now()+Duration::from_secs(1);continue;}else{break;}} true
        } else {s.local_hits+=wanted.iter().filter(|k|cache.contains_key(k)).count()as u64;false};
        let complete=wanted.iter().all(|k|cache.contains_key(k));if complete {s.coverage_complete_cycles+=1;}else{s.incomplete_cycles+=1;}
        let ms=load_start.elapsed().as_secs_f64()*1000.;if s.first_coarse_ms.is_none()&&cache.contains_key(&wanted[0]) {s.first_coarse_ms=Some(ms);}
        if s.first_detail_ms.is_none()&&complete {s.first_detail_ms=Some(ms);}
        s.latency_ms.push(before.elapsed().as_secs_f64()*1000.);
        let pause=if opts.case=="slow"&&id%10==0 {Duration::from_millis(1500)} else {Duration::from_millis(1000)};
        if !online {tokio::time::sleep(Duration::from_millis(100)).await;}else{tokio::time::sleep(pause).await;}
    }
    if let Some(t)=connecting {t.abort();let _=t.await;}
    s.load_elapsed_seconds=load_start.elapsed().as_secs_f64();s.cache_entries=cache.len();
    if opts.case=="convergence" {
        let validation=std::fs::read(opts.world.parent().unwrap().join("expected.json"));
        match validation.and_then(|b|serde_json::from_slice::<Vec<(Key,[u8;32])>>(&b).map_err(std::io::Error::other)) {
            Ok(items)=>{for(k,hash)in items {if cache.contains_key(&k) {s.final_expected+=1;if cache[&k].cells==hash{s.final_matching+=1;}}}},
            Err(e)=>s.failures.push(format!("final source validation: {e}")),
        }
    }
    s.cache_entries=cache.len();if let Err(e)=save_cache(&opts.cache,id,&cache){s.failures.push(e.to_string());}
    if let Some(p)=peer {opts.connected_now.fetch_sub(1,Ordering::Relaxed);p.conn.close(0u32.into(),b"load finished");}s
}

async fn old_server(world:PathBuf,data:PathBuf)->Result<()> {
    let reg=Arc::new(RwLock::new(Registry::open(data.join("catalog"))?));
    let dims=BTreeMap::from([(DIM.into(),Arc::new(AnvilWorld::new(DIM.into(),world)))]);
    let service=Arc::new(RegionalService::open(&data,&dims,reg.clone())?);service.start(Duration::from_secs(2))?;
    let cat=reg.read().unwrap().catalog_id();let state=Arc::new(ServerState::new(&dims,cat,service));
    server::serve(state,"127.0.0.1:0".parse()?,&data.join("quic"),std::future::pending()).await
}
fn is_full(world:&Path)->Result<bool> {let p=std::fs::read_dir(world.join("region"))?.filter_map(Result::ok).find(|e|e.path().extension().is_some_and(|e|e=="mca")).context("fixture region")?.path();
    let b=std::fs::read(p)?;Ok(b[..4096].chunks_exact(4).filter(|c|c[..3]!=[0;3]).count()==1024)}
fn expected(world:&Path,data:&Path)->Result<()> {
    let reg=Arc::new(RwLock::new(Registry::open(data.join("validation-catalog"))?));let source=AnvilWorld::new(DIM.into(),world.to_path_buf());
    let mut out=Vec::new();
    let full=is_full(world)?;let side=if full {16}else{4};
    for i in 0..100 {let(rx,rz)=region(i);for z in 0..side {for x in 0..side {
        let gx=rx*16+x;let gz=rz*16+z;let group=source.load_level_zero_group(gx,gz,&reg)?;
        for y in 0..2 {let built=group.build(SectionKey::new(0,gx,y,gz)?,&source)?;
            let snapshot=reg.read().unwrap().snapshot();let cat=Catalog::from_snapshot(&snapshot)?;
            let names=Names{blocks:cat.blocks.into_iter().map(|b|b.canonical).collect(),biomes:cat.biomes};
            out.push(((0,gx,y,gz),digest_cells(&built.section.cells,&names,true)?));
        }
    }}}
    std::fs::write(data.join("expected.json"),serde_json::to_vec(&out)?)?;Ok(())
}
#[tokio::main]
async fn main()->Result<()> {
    let a=std::env::args().skip(1).collect::<Vec<_>>();
    if a.first().map(String::as_str)==Some("old-server") {return old_server(a[1].clone().into(),a[2].clone().into()).await;}
    if a.first().map(String::as_str)==Some("expected") {return expected(Path::new(&a[1]),Path::new(&a[2]));}
    if a.len()!=9 {bail!("protocol case seconds address cert world cache clients report");}
    let protocol=a[0].clone();let case=a[1].clone();let seconds=a[2].parse()?;let addresses:Vec<std::net::SocketAddr>=if a[3].starts_with("@") {serde_json::from_slice::<Vec<String>>(&std::fs::read(&a[3][1..])?)?.into_iter().map(|s|s.parse()).collect::<std::result::Result<_,_>>()?}else{vec![a[3].parse()?]};
    let cert=std::fs::read(&a[4])?;let mut roots=rustls::RootCertStore::empty();roots.add(CertificateDer::from(cert))?;
    let mut tls=rustls::ClientConfig::builder().with_root_certificates(roots).with_no_client_auth();
    tls.alpn_protocols=vec![if protocol=="old" {ALPN.to_vec()}else{b"voxy-rewrite-1".to_vec()}];
    let crypto=quinn::crypto::rustls::QuicClientConfig::try_from(tls)?;
    let mut config=quinn::ClientConfig::new(Arc::new(crypto));let mut transport=quinn::TransportConfig::default();
    if case=="slow" {transport.stream_receive_window(4096u32.into());transport.receive_window(65536u32.into());}
    transport.keep_alive_interval(Some(Duration::from_secs(5)));transport.max_idle_timeout(Some(Duration::from_secs(300).try_into()?));config.transport_config(Arc::new(transport));
    let mut endpoint=Endpoint::client("127.0.0.1:0".parse()?)?;endpoint.set_default_client_config(config);
    let clients=a[7].parse()?;let start=Instant::now();
    let settings=Settings{protocol:protocol.clone(),case:case.clone(),seconds,start,load_start:Arc::new(OnceLock::new()),world:a[5].clone().into(),cache:a[6].clone().into(),full:is_full(Path::new(&a[5]))?,impaired:a[3].starts_with("@"),connected_now:Arc::new(AtomicUsize::new(0)),connected_peak:Arc::new(AtomicUsize::new(0))};
    let barrier=Arc::new(tokio::sync::Barrier::new(clients));let mut tasks=Vec::new();
    for id in 0..clients {tasks.push(tokio::spawn(virtual_client(id,endpoint.clone(),addresses[if addresses.len()==1 {0}else{id}],settings.clone(),barrier.clone())));}
    let mut results=Vec::new();for t in tasks {results.push(t.await?);}
    let mut latency=results.iter().flat_map(|s|s.latency_ms.iter().copied()).collect::<Vec<_>>();latency.sort_by(f64::total_cmp);
    let quantile=|q:f64|latency.get(((latency.len().saturating_sub(1))as f64*q)as usize).copied();
    let report=serde_json::json!({"protocol":protocol,"case":case,"clients":clients,"elapsed_seconds":start.elapsed().as_secs_f64(),
        "all_connected":settings.connected_peak.load(Ordering::Relaxed)==clients,"connected_peak":settings.connected_peak.load(Ordering::Relaxed),"ever_connected_clients":results.iter().filter(|s|s.connected).count(),"error_count":results.iter().map(|s|s.failures.len()).sum::<usize>(),"load_seconds":seconds,"simultaneous_start_barrier":true,
        "sections":results.iter().map(|s|s.sections).sum::<u64>(),"payload_bytes":results.iter().map(|s|s.payload_bytes).sum::<u64>(),
        "latency_cycle_ms":{"p50":quantile(0.5),"p95":quantile(0.95),"p99":quantile(0.99)},"per_client":results});
    std::fs::write(&a[8],serde_json::to_vec_pretty(&report)?)?;println!("{}",serde_json::to_string(&serde_json::json!({"report":a[8],"clients":clients,"case":case,"sections":report["sections"],"payload_bytes":report["payload_bytes"],"all_connected":report["all_connected"],"latency_cycle_ms":report["latency_cycle_ms"]}))?);
    Ok(())
}
