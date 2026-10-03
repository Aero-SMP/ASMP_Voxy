#!/usr/bin/env python3
"""100 independent real UDP paths: seeded loss, propagation delay, link serialization."""
import argparse,asyncio,json,random,signal,time
from pathlib import Path

class Link:
    def __init__(self,i,loss,bps,delay):
        self.id=i;self.loss=loss;self.bps=bps;self.delay=delay;self.client=None
        self.random=[random.Random(244+i*2),random.Random(245+i*2)]
        self.next=[0.,0.];self.stats=[dict(received=0,dropped=0,delivered=0,received_bytes=0,delivered_bytes=0,
            minimum_forward_delay_ms=None,maximum_forward_delay_ms=0)for _ in range(2)]
    def forward(self,data,direction,transport,address):
        s=self.stats[direction];s['received']+=1;s['received_bytes']+=len(data)
        loop=asyncio.get_running_loop();now=loop.time()
        serialized=max(now,self.next[direction])+len(data)*8/self.bps
        self.next[direction]=serialized
        if self.random[direction].random()<self.loss:
            s['dropped']+=1;return
        def deliver():
            transport.sendto(data,address);s['delivered']+=1;s['delivered_bytes']+=len(data)
            elapsed=(loop.time()-now)*1000
            s['minimum_forward_delay_ms']=min(s['minimum_forward_delay_ms']or elapsed,elapsed)
            s['maximum_forward_delay_ms']=max(s['maximum_forward_delay_ms'],elapsed)
        loop.call_at(serialized+self.delay,deliver)

class Receiver(asyncio.DatagramProtocol):
    def __init__(self,link,direction,upstream):self.link=link;self.direction=direction;self.upstream=upstream
    def connection_made(self,transport):self.transport=transport
    def datagram_received(self,data,address):
        if self.direction==0:
            self.link.client=address;self.link.forward(data,0,self.link.up_transport,self.upstream)
        elif self.link.client is not None:self.link.forward(data,1,self.link.down_transport,self.link.client)

async def main(a):
    loop=asyncio.get_running_loop();stop=asyncio.Event();links=[];addresses=[];started=time.time()
    for sig in(signal.SIGINT,signal.SIGTERM):loop.add_signal_handler(sig,stop.set)
    for i in range(a.clients):
        loss=a.loss_min+(a.loss_max-a.loss_min)*i/max(1,a.clients-1)
        link=Link(i,loss,a.mbps*1_000_000,a.delay_ms/1000)
        up,_=await loop.create_datagram_endpoint(lambda:Receiver(link,1,None),local_addr=('127.0.0.1',0))
        link.up_transport=up
        down,_=await loop.create_datagram_endpoint(lambda:Receiver(link,0,tuple(a.server.split(':')[:1]+[int(a.server.split(':')[1])])),local_addr=('127.0.0.1',0))
        link.down_transport=down;links.append(link);addresses.append(f"127.0.0.1:{down.get_extra_info('sockname')[1]}")
    a.ports.write_text(json.dumps(addresses)+'\n')
    print(json.dumps({'event':'impairment_ready','clients':a.clients,'loss_min':a.loss_min,'loss_max':a.loss_max,'directional_mbps':a.mbps,
        'delay_each_direction_ms':a.delay_ms,'minimum_rtt_ms':a.delay_ms*2,'ports':str(a.ports)}),flush=True)
    with a.report.with_suffix('.jsonl').open('w')as f:
        while not stop.is_set():
            f.write(json.dumps({'elapsed':time.time()-started,'clients':[{'id':l.id,'loss_probability_each_direction':l.loss,'directions':l.stats}for l in links]})+'\n');f.flush()
            try:await asyncio.wait_for(stop.wait(),1)
            except asyncio.TimeoutError:pass
    a.report.write_text(json.dumps({'started_epoch':started,'elapsed':time.time()-started,'seed':244,
        'loss_applies_to':'everyQUICdatagram includingTLS/ACKs, independentlyeachdirection',
        'directional_mbps':a.mbps,'delay_each_direction_ms':a.delay_ms,'minimum_rtt_ms':a.delay_ms*2,
        'clients':[{'id':l.id,'loss_probability_each_direction':l.loss,'directions':l.stats}for l in links]},indent=2)+'\n')
    for l in links:l.up_transport.close();l.down_transport.close()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('server');p.add_argument('--clients',type=int,default=100);p.add_argument('--ports',type=Path,required=True);p.add_argument('--report',type=Path,required=True)
    p.add_argument('--loss-min',type=float,default=.5);p.add_argument('--loss-max',type=float,default=.9);p.add_argument('--mbps',type=float,default=3);p.add_argument('--delay-ms',type=float,default=500)
    a=p.parse_args();assert 0<=a.loss_min<=a.loss_max<=1 and a.mbps>0 and a.delay_ms>=0
    asyncio.run(main(a))
