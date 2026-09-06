package me.cortex.voxy.client.lod;

import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.lang.reflect.*;
import static me.cortex.voxy.client.lod.CacheStartupBehaviorTest.*;

/** Production private readers and independent file envelopes, with optional query/allocation probes. */
final class CacheKeyMetadataBehaviorTest {
    static void run() throws Exception {
        keyIdentityAndEncoding();
        metadataEnvelopes();
        if (InventoryQueryAgent.active) replayBenchmark();
        System.out.println("flat-key identity/encoding/replay and metadata snapshot tests passed");
    }
    private static ByteBuffer le(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
    private static byte[] shardHeader() {
        return le(64).put(new byte[]{'V','X','Y','S','E','C',0,0}).putLong(1).putLong(2).putLong(3).putLong(4)
                .putInt(0).putInt(0).putLong(0).array();
    }
    private static Object call(AutoCloseable shard, String name, Object key, Object... extra) throws Exception {
        var types = extra.length == 0 ? new Class<?>[]{key.getClass()} : new Class<?>[]{key.getClass(), byte[].class};
        Method method = shard.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
        return extra.length == 0 ? invoke(method, shard, key) : invoke(method, shard, key, extra[0]);
    }
    private static void record(ByteArrayOutputStream out, long low, long high, int length, byte[] payload) {
        out.writeBytes(le(20).putLong(low).putLong(high).putInt(length).array()); out.writeBytes(payload);
    }
    private static void keyIdentityAndEncoding() throws Exception {
        Path root = Files.createTempDirectory("voxy-flat-key-"); Path path = root.resolve("r.0.0.vxcache");
        long[][] values = {{0,0}, {1,0}, {0,1}, {-1,0}, {0,-1}, {Long.MIN_VALUE,Long.MAX_VALUE}, {1,2}};
        try {
            Files.write(path, shardHeader()); var expected = new ByteArrayOutputStream(); expected.writeBytes(shardHeader());
            try (var shard = openShard(path, true)) {
                for (long[] v : values) for (int length : new int[]{4, 7}) {
                    byte[] body = new byte[length]; Arrays.fill(body, (byte) (v[0] ^ v[1] ^ length));
                    Object key = shardKey(v[0], v[1], length);
                    check((boolean) call(shard, "put", key, body), "new identity lost");
                    check(!(boolean) call(shard, "put", key, new byte[length]), "duplicate replaced original");
                    record(expected, v[0], v[1], length, body);
                }
                call(shard, "remove", shardKey(1, 2, 4)); record(expected, 1, 2, -4, new byte[0]);
                byte[] replacement = {9,8,7,6}; call(shard, "put", shardKey(1,2,4), replacement); record(expected,1,2,4,replacement);
            }
            check(Arrays.equals(Files.readAllBytes(path), expected.toByteArray()), "append/tombstone format differs from independent legacy encoding");
            String export = System.getProperty("voxy.cacheInteropOutput");
            if (export != null) Files.copy(path, Path.of(export), StandardCopyOption.REPLACE_EXISTING);
            String input = System.getProperty("voxy.cacheInteropInput");
            if (input != null) Files.copy(Path.of(input), path, StandardCopyOption.REPLACE_EXISTING);
            try (var shard = openShard(path, false)) {
                for (long[] v : values) for (int length : new int[]{4,7}) {
                    byte[] body = new byte[length]; Arrays.fill(body, (byte) (v[0] ^ v[1] ^ length));
                    if (v[0] == 1 && v[1] == 2 && length == 4) body = new byte[]{9,8,7,6};
                    check(Arrays.equals(body, (byte[]) call(shard,"get",shardKey(v[0],v[1],length))), "128-bit/length identity corrupted");
                }
                check(call(shard,"get",shardKey(1,2,5)) == null, "missing length lost sentinel");
            }
            // The generated record hash collides intentionally; equality must still compare every field.
            Files.write(path, shardHeader());
            try (var shard = openShard(path, true)) {
                Integer hash = null;
                for (int i = 0; i < 256; i++) {
                    Object key = shardKey(i, Integer.toUnsignedLong(-31 * i), 4);
                    if (hash == null) hash = key.hashCode(); else check(hash == key.hashCode(), "fixture not a hash collision");
                    call(shard,"put",key,le(4).putInt(i).array());
                }
            }
            try (var shard = openShard(path, false)) {
                for (int i = 0; i < 256; i++) check(Arrays.equals(le(4).putInt(i).array(),
                        (byte[]) call(shard,"get",shardKey(i,Integer.toUnsignedLong(-31*i),4))), "colliding key lost after replay");
            }
        } finally { cleanup(root); }
    }

    private static byte[] envelope(int kind, byte[] body) {
        return le(24 + body.length).putLong(0x3154524154535856L).putInt(1).putInt(kind).putInt(body.length)
                .putInt(RegionalProtocol.crc32c(body)).put(body).array();
    }
    private static byte[] read(RegionalMetadataStore store, Path path, int maximum) throws Exception {
        Method read = RegionalMetadataStore.class.getDeclaredMethod("read", Path.class, int.class, int.class); read.setAccessible(true);
        return (byte[]) invoke(read, store, path, 2, maximum);
    }
    private static void metadataEnvelopes() throws Exception {
        Path root = Files.createTempDirectory("voxy-metadata-size-"); Path path = root.resolve("test.vxcat");
        try (var store = new RegionalMetadataStore(root)) {
            awaitInventory(store.budget);
            for (int length : new int[]{0,1,100,4096}) {
                byte[] body = new byte[length]; Arrays.fill(body, (byte) 71);
                Files.write(path, envelope(2,body));
                int[] counts = InventoryQueryAgent.metadataQueries.get(); Arrays.fill(counts,0);
                check(Arrays.equals(body,read(store,path,4096)), "valid metadata body changed");
                if (InventoryQueryAgent.active) check(counts[0] == Integer.getInteger("voxy.metadataReadSizeQueries",1), "read size call count: " + counts[0]);
            }
            byte[] body = new byte[100], valid = envelope(2,body);
            List<byte[]> invalid = new ArrayList<>();
            for (int n : new int[]{0,1,23,24,50,123}) invalid.add(Arrays.copyOf(valid,n));
            invalid.add(Arrays.copyOf(valid,valid.length+1));
            for (int offset : new int[]{0,8,12,20,valid.length-1}) { byte[] b=valid.clone(); b[offset]^=1; invalid.add(b); }
            for (int length : new int[]{-1,Integer.MIN_VALUE,Integer.MAX_VALUE,4097,99,101}) {
                byte[] b=valid.clone(); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(16,length); invalid.add(b);
            }
            for (byte[] bytes : invalid) { Files.write(path,bytes); check(read(store,path,4096)==null,"invalid metadata accepted"); }
            Files.delete(path); check(read(store,path,4096)==null,"missing metadata accepted");
            Path descriptor=root.resolve("r.0.0.vxmeta");
            for (boolean referenced : new boolean[]{false,true}) {
                byte[] prefix=le(100).position(64).putLong(referenced?1:0).putLong(0).putLong(0).putLong(0).array();
                Files.write(descriptor,envelope(3,prefix));
                int[] counts=InventoryQueryAgent.metadataQueries.get(); Arrays.fill(counts,0);
                Path found=RegionalMetadataStore.referencedCatalog(descriptor);
                check((found!=null)==referenced,"catalog reference presence changed");
                if (referenced) check(found.getFileName().toString().equals("0100000000000000000000000000000000000000000000000000000000000000.vxcat"),"reference bytes changed");
                if(InventoryQueryAgent.active) check(counts[1]==Integer.getInteger("voxy.metadataReferenceSizeQueries",1),"reference size count");
            }
            if (InventoryQueryAgent.active) {
                Files.write(path,valid);
                Path replacement=root.resolve("replacement"); Files.write(replacement,envelope(2,new byte[]{9,8,7}));
                InventoryQueryAgent.afterMetadataSize.set(() -> {
                    try { Files.move(replacement,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                    catch (IOException failure) { throw new UncheckedIOException(failure); }
                });
                try { check(Arrays.equals(read(store,path,4096),body),"atomic replace mixed opened generations"); }
                catch (UncheckedIOException platform) { System.out.println("SKIP open-file replacement platform: "+platform); }
                finally { InventoryQueryAgent.afterMetadataSize.remove(); }
                Files.write(path,valid);
                InventoryQueryAgent.afterMetadataSize.set(() -> {
                    try (FileChannel channel=FileChannel.open(path,StandardOpenOption.WRITE)) { channel.truncate(3); }
                    catch(IOException failure) { throw new UncheckedIOException(failure); }
                });
                try { check(read(store,path,4096)==null,"truncated-after-size metadata accepted"); }
                catch(IOException expected) { /* Full read must fail safely, not return mixed bytes. */ }
                finally { InventoryQueryAgent.afterMetadataSize.remove(); }
                Files.write(path,valid);
                for(int run=0;run<7;run++) {
                    long start=System.nanoTime();
                    for(int i=0;i<1000;i++) { read(store,path,4096); RegionalMetadataStore.referencedCatalog(descriptor); }
                    System.out.println("METADATA_BENCH run="+run+" ns/pair="+(System.nanoTime()-start)/1000);
                }
            }
        } finally { cleanup(root); }
    }
    private static void replayBenchmark() throws Exception {
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        boolean supported=bean.isThreadAllocatedMemorySupported()&&bean.isThreadAllocatedMemoryEnabled();
        Path root=Files.createTempDirectory("voxy-key-replay-"); Path path=root.resolve("r.0.0.vxcache");
        try {
            int entries=32768; ByteBuffer bytes=le(64+entries*24).put(shardHeader());
            for(int i=0;i<entries;i++) bytes.putLong(i).putLong(Long.rotateLeft(i*0x9e3779b97f4a7c15L,17)).putInt(4).putInt(i);
            Files.write(path,bytes.array());
            for(int run=0;run<7;run++) {
                long before=supported?bean.getThreadAllocatedBytes(Thread.currentThread().threadId()):-1,start=System.nanoTime();
                try(var shard=openShard(path,false)) {
                    long nanos=System.nanoTime()-start, allocated=supported?bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-before:-1;
                    Field records=shard.getClass().getDeclaredField("records"); records.setAccessible(true);
                    var keys=((Map<?,?>)records.get(shard)).keySet();
                    long retained=0; int nested=0;
                    for(Object key:keys) {
                        retained+=InventoryQueryAgent.instrumentation.getObjectSize(key);
                        for(var component:key.getClass().getRecordComponents()) if(!component.getType().isPrimitive()) {
                            Method accessor=component.getAccessor(); accessor.setAccessible(true);
                            retained+=InventoryQueryAgent.instrumentation.getObjectSize(accessor.invoke(key)); nested++;
                        }
                    }
                    check(nested==(Boolean.getBoolean("voxy.baselineKeys")?entries:0),"nested fingerprint keys remain");
                    System.out.println("KEY_BENCH run="+run+" entries="+keys.size()+" ns="+nanos+" allocated="+allocated+" keyGraphBytes="+retained+" nested="+nested);
                }
            }
        } finally { cleanup(root); }
    }
}
