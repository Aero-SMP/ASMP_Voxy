package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker.Window;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker.Planner;
import me.cortex.voxy.client.core.rendering.SectionKey;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.*;
import java.lang.management.ManagementFactory;
import static me.cortex.voxy.client.lod.DebugSnapshotShutdownBehaviorTest.*;

/** Production planner/callback authority, with deterministic slice budgets and GPU-free oracles. */
public final class TerrainWindowPlannerBehaviorTest {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void await(CountDownLatch latch) {
        try { check(latch.await(10, java.util.concurrent.TimeUnit.SECONDS), "barrier timed out"); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
    static void join(Thread thread) throws InterruptedException {
        thread.join(10000); check(!thread.isAlive(), "worker failed to stop");
    }
    public static void main(String[] args) throws Exception { run(); }
    static void run() throws Exception {
        for (int phase : new int[]{1, 3, 4}) for (int extra : new int[]{0, 1, 7}) reverse(phase, extra);
        everyRadiusAndPriority();
        seededChurn();
        stopAndCallbackFailure();
        scopedCallbacks();
        ownerRunAndRenderSync();
        benchmark();
        System.out.println("terrain window planner correctness/ordering/cancellation/lifecycle passed");
    }

    static final class Fixture {
        final AtomicReference<Window> latest = new AtomicReference<>();
        final AtomicBoolean running = new AtomicBoolean(true);
        final Set<Long> roots = new HashSet<>();
        final List<Long> changes = new ArrayList<>();
        Runnable duringEnter = () -> {};
        final Planner planner = new Planner(-1, 1, key -> {
            check(roots.add(key), "duplicate root addition " + key);
            changes.add(key); duringEnter.run();
        }, key -> {
            check(roots.remove(key), "missing root removal " + key);
            changes.add(key | Long.MIN_VALUE);
        }, latest::get, running::get);
        void target(int x, int z, int r) { latest.set(new Window(x,z,r)); }
        void step(int budget) { planner.process(budget, Long.MAX_VALUE); }
        void settle() {
            int rounds = 0;
            while (planner.hasWork()) { step(512); check(++rounds < 100000, "planner did not settle"); }
            check(roots.equals(circle(latest.get())), "final topology differs from exact circle");
        }
    }

    static Set<Long> circle(Window window) {
        Set<Long> result = new HashSet<>();
        for (int x=window.x()-window.radius(); x<=window.x()+window.radius(); x++) {
            for (int z=window.z()-window.radius(); z<=window.z()+window.radius(); z++) {
                if (window.contains(Integer.toUnsignedLong(x) | (Integer.toUnsignedLong(z)<<32))) {
                    for(int y=-1;y<=1;y++) result.add(SectionKey.pack(4,x,y,z));
                }
            }
        }
        return result;
    }

    static void reverse(int phase, int extra) throws Exception {
        Fixture f = new Fixture(); f.target(-2,-3,3); f.settle();
        Window a = f.latest.get(); f.target(100,-100,7);
        for(int guard=0;(int)get(f.planner,"phase")!=phase;guard++) {
            f.step(1); check(guard<100000,"phase not reached");
        }
        f.step(extra);
        f.latest.set(a);
        f.settle();
        check(f.roots.equals(circle(a)),"partial reversal lost applied prefix");
        // Target changes inside an executing callback: it may finish, but no next old op.
        f.target(30,30,5); AtomicBoolean once = new AtomicBoolean();
        f.duringEnter = () -> { if(once.compareAndSet(false,true)) f.latest.set(a); };
        f.settle();
    }

    static int priority(long a, long b, Window w) {
        if ((a<0)!=(b<0)) return a<0?1:-1;
        long ax=(long)SectionKey.x(a)-w.x(), az=(long)SectionKey.z(a)-w.z();
        long bx=(long)SectionKey.x(b)-w.x(), bz=(long)SectionKey.z(b)-w.z();
        int c=Long.compare(ax*ax+az*az,bx*bx+bz*bz);
        if(c!=0)return a<0?-c:c;
        long ak=Integer.toUnsignedLong(SectionKey.x(a))|(Integer.toUnsignedLong(SectionKey.z(a))<<32);
        long bk=Integer.toUnsignedLong(SectionKey.x(b))|(Integer.toUnsignedLong(SectionKey.z(b))<<32);
        c=Long.compareUnsigned(ak,bk);
        return c!=0?c:Integer.compare(SectionKey.y(a),SectionKey.y(b));
    }

    static void everyRadiusAndPriority() {
        Fixture f=new Fixture(); f.target(0,0,2); f.settle();
        for(int r=2;r<=65;r++) {
            Set<Long> old=new HashSet<>(f.roots); f.changes.clear();
            f.target(r%3-1,-r%4,r); Window w=f.latest.get(); Set<Long> next=circle(w);
            var expected=new ArrayList<Long>();
            for(long key:next)if(!old.contains(key))expected.add(key);
            for(long key:old)if(!next.contains(key))expected.add(key|Long.MIN_VALUE);
            expected.sort((a,b)->priority(a,b,w));
            long builds=f.planner.orderingBuilds;f.settle();
            check(f.changes.equals(expected),"priority order changed at radius "+r);
            check(f.planner.orderingBuilds==builds+1,"stable target ordered repeatedly");
        }
        long visits=f.planner.differenceVisits, builds=f.planner.orderingBuilds;
        for(int i=0;i<1000;i++)f.step(512);
        check(visits==f.planner.differenceVisits && builds==f.planner.orderingBuilds,"idle scanning");
        f.target(0,0,65);f.settle();visits=f.planner.differenceVisits;
        f.target(1,0,65);f.settle();
        check(f.planner.differenceVisits-visits<2000,"single step scanned whole resident circle");
    }

    static void seededChurn() {
        for(int seed:new int[]{8192,17,65537}) {
            Fixture f=new Fixture();Random random=new Random(seed);
            for(int i=0;i<200;i++) {
                f.target(random.nextInt(40)-20,random.nextInt(40)-20,2+random.nextInt(10));
                f.step(1+random.nextInt(150));
                if(i%20==0)f.settle();
            }
            f.settle();
        }
    }

    static void stopAndCallbackFailure() {
        Fixture f=new Fixture();f.target(0,0,65);f.step(1);f.running.set(false);
        long work=f.planner.differenceVisits;f.step(512);
        check(work==f.planner.differenceVisits && f.roots.isEmpty(),"stopped preparation continued");
        AtomicReference<Window> target=new AtomicReference<>(new Window(0,0,2));
        RuntimeException injected=new RuntimeException("injected terrain callback failure");
        Planner p=new Planner(0,0,k->{throw injected;},k->{},target::get,()->true);
        try { while(p.hasWork())p.process(512,Long.MAX_VALUE);throw new AssertionError("failure swallowed"); }
        catch(RuntimeException e){check(e==injected && p.appliedOperations==0,"failed op marked applied");}
    }

    static void scopedCallbacks() throws Exception {
        ClientSession.resetDemand();
        var old=allocate(VoxyRenderSystem.class);var next=allocate(VoxyRenderSystem.class);
        long key=SectionKey.pack(4,0,0,0),second=SectionKey.pack(4,1,0,0);
        ClientSession.attachRenderer(old);
        check(ClientSession.sectionEntered(old,key),"pre-connect root discarded");
        var first=new ClientSession.Session(9001,"test",old,null,null,0);
        var initial=new LinkedHashMap<Long,Boolean>();first.demands.drainTop(initial::put);
        check(initial.equals(Map.of(key,true)),"pre-connect snapshot missing");
        CountDownLatch paused=new CountDownLatch(1),resume=new CountDownLatch(1);
        Thread late=new Thread(()->{paused.countDown();await(resume);ClientSession.sectionEntered(old,second);ClientSession.sectionLeft(old,key);});
        late.start();await(paused);
        ClientSession.attachRenderer(next);ClientSession.sectionEntered(next,key);
        resume.countDown();join(late);ClientSession.stopRenderer(old);
        var successor=new ClientSession.Session(9002,"test",next,null,null,0);
        initial.clear();successor.demands.drainTop(initial::put);
        check(initial.equals(Map.of(key,true)),"old renderer mutated successor roots");
        ClientSession.stopRenderer(next);
        check(!ClientSession.sectionEntered(next,second),"stopped owner regained authority");
        ClientSession.resetDemand();
    }

    static void ownerRunAndRenderSync() throws Exception {
        var async=manager();
        var geometry=(me.cortex.voxy.client.core.rendering.section.BasicAsyncGeometryManager)get(async,"geometryManager");
        var nodes=new me.cortex.voxy.client.core.rendering.hierarchical.NodeManager(65536,geometry);
        set(async,"manager",nodes);
        set(async,"geometryData",allocate(me.cortex.voxy.client.core.rendering.section.BasicSectionGeometryData.class));
        set(async,"tlnIdChange",new it.unimi.dsi.fastutil.ints.IntOpenHashSet());
        set(async,"cleanerIdResetClear",new it.unimi.dsi.fastutil.ints.IntOpenHashSet());
        set(async,"workerIdleNanos",new AtomicLong());
        var resultType=Arrays.stream(async.getClass().getDeclaredClasses()).filter(c->c.getSimpleName().equals("SyncResults")).findFirst().orElseThrow();
        var constructor=resultType.getDeclaredConstructor();constructor.setAccessible(true);
        set(async,"resultCache1",constructor.newInstance());set(async,"resultCache2",constructor.newInstance());
        AtomicReference<Thread> owner=new AtomicReference<>();
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor(task->{
            Thread t=new Thread(task,"planner-test-hierarchy-owner");owner.set(t);return t;
        });
        AtomicInteger callbacks=new AtomicInteger();
        async.configureTerrainPlanner(0,0,k->{
            check(Thread.currentThread()==owner.get(),"topology callback ran on renderer");callbacks.incrementAndGet();
        },k->check(Thread.currentThread()==owner.get(),"retirement callback ran on renderer"));
        var run=async.getClass().getDeclaredMethod("run");run.setAccessible(true);
        java.util.concurrent.Callable<Void> pass=()->{run.invoke(async);return null;};
        try {
            async.terrainWindowChanged(new Window(0,0,65));
            executor.submit(pass).get(10,java.util.concurrent.TimeUnit.SECONDS);
            set(async,"thread",owner.get());
            check(get(async,"results")==null && ((AtomicLong)get(async,"topologyGeneration")).get()==0,
                    "preparation manufactured hierarchy/GPU progress");
            check(((AtomicBoolean)get(async,"workPending")).get(),"preparation-only pass lost runnable work");
            long key=SectionKey.pack(4,0,0,0);
            async.retirePublication(500,499,key,()->{},error->{throw new AssertionError(error);});
            async.coarsenSubtree(501,key,()->{},error->{throw new AssertionError(error);});
            executor.submit(pass).get(10,java.util.concurrent.TimeUnit.SECONDS);
            Object result=get(async,"results");
            check(result!=null && ((List<?>)get(result,"rendererTransactions")).size()==2 && callbacks.get()==0,
                    "planning starved actual retirement/coarsening queues");
            // Mock only fence completion: these no-geometry transactions have no GL payload.
            ((List<?>)get(result,"rendererTransactions")).clear();
            set(async,"needsWaitForSync",true);
            Planner planner=(Planner)get(async,"terrainPlanner");
            java.util.concurrent.Future<Void> blocked;
            long deadline=System.nanoTime()+10_000_000_000L;
            while(true) {
                blocked=executor.submit(pass);
                while(!blocked.isDone() && !(boolean)get(async,"waitingForRenderSync")) {
                    check(System.nanoTime()<deadline,"owner failed to reach sync boundary");
                    Thread.onSpinWait();
                }
                if((boolean)get(async,"waitingForRenderSync"))break;
                blocked.get(10,java.util.concurrent.TimeUnit.SECONDS);
            }
            int before=callbacks.get();
            Window last=new Window(-2,3,2);
            for(int i=0;i<100;i++)async.terrainWindowChanged(new Window(i,0,2));
            async.terrainWindowChanged(last);
            check(callbacks.get()==before && get(async,"terrainTarget")==last,
                    "blocked sync applied work or accumulated target history");
            async.tick(null,null); // Actual result acquire/cache recycling, no GL payload.
            blocked.get(10,java.util.concurrent.TimeUnit.SECONDS);
            for(int i=0;planner.hasWork();i++) {
                check(i<100000,"owner did not settle after render resumed");
                async.tick(null,null);
                executor.submit(pass).get(10,java.util.concurrent.TimeUnit.SECONDS);
            }
            async.tick(null,null);
            var roots=(it.unimi.dsi.fastutil.longs.LongOpenHashSet)get(nodes,"topLevelNodes");
            Set<Long> expected=new HashSet<>();for(long k:circle(last))if(SectionKey.y(k)==0)expected.add(k);
            check(new HashSet<>(roots).equals(expected),"actual NodeManager lost final coverage");
        } finally {
            async.beginStopping();
            executor.shutdown();check(executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS),"test owner leaked");
            async.stop();
        }
    }

    static void benchmark() {
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        AtomicReference<Window> target=new AtomicReference<>();long[] callbacks={0};
        Planner planner=new Planner(0,1,k->callbacks[0]++,k->callbacks[0]++,target::get,()->true);
        var tracker=new RenderDistanceTracker(target::set);tracker.setRenderDistance(65);
        for(int repeat=0;repeat<4;repeat++) {
            long before=callbacks[0];
            long bytes=bean.getThreadAllocatedBytes(Thread.currentThread().threadId()),cpu=bean.getCurrentThreadCpuTime();
            long submit=0,max=0;int frames=0;
            for(double x:new double[]{0,16384,0,512,0}) {
                long start=System.nanoTime();tracker.setCenter(x,0);long elapsed=System.nanoTime()-start;
                submit+=elapsed;max=Math.max(max,elapsed);frames++;
                while(planner.hasWork())planner.process(512,Long.MAX_VALUE);
            }
            System.out.println("candidate reuse repeat="+repeat+" callbacks="+(callbacks[0]-before)+" frames="+frames+" submitNs="+submit+" maxFrameNs="+max+" cpuNs="+(bean.getCurrentThreadCpuTime()-cpu)+" allocatedBytes="+(bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-bytes)+" builds="+planner.orderingBuilds+" diffVisits="+planner.differenceVisits+" orderVisits="+planner.orderingVisits);
        }
    }
}
