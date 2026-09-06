package me.cortex.voxy.client.lod;

import java.lang.instrument.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

/** Optional test agent: explicit inventory/metadata queries and deterministic post-size faults. */
public final class InventoryQueryAgent {
    static volatile boolean active;
    static Instrumentation instrumentation;
    static final ThreadLocal<int[]> metadataQueries = ThreadLocal.withInitial(() -> new int[2]);
    static final ThreadLocal<Runnable> afterMetadataSize = new ThreadLocal<>();
    static volatile long reads, heapBytes, elapsedNanos;
    private static long started, allocated;
    private static final com.sun.management.ThreadMXBean BEAN =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();

    public static void premain(String args, Instrumentation instrumentation) {
        InventoryQueryAgent.instrumentation = instrumentation;
        active = true;
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] bytes) {
                if (!name.equals("me/cortex/voxy/client/lod/RegionalDiskBudget")
                        && !name.equals("me/cortex/voxy/client/lod/RegionalMetadataStore")) return null;
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, method, desc, signature, exceptions)) {
                            private void hook(String hook) { super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "me/cortex/voxy/client/lod/InventoryQueryAgent", hook, "()V", false); }
                            @Override public void visitCode() { super.visitCode(); if (method.equals("inventory")) hook("begin"); }
                            @Override public void visitInsn(int opcode) {
                                if (method.equals("inventory") && opcode == Opcodes.RETURN) hook("end");
                                super.visitInsn(opcode);
                            }
                            @Override public void visitMethodInsn(int opcode, String owner, String called, String descriptor, boolean isInterface) {
                                if (name.endsWith("/RegionalMetadataStore") && owner.equals("java/nio/channels/FileChannel")
                                        && called.equals("size") && (method.equals("read") || method.equals("referencedCatalog"))) {
                                    super.visitInsn(method.equals("read") ? Opcodes.ICONST_0 : Opcodes.ICONST_1);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "me/cortex/voxy/client/lod/InventoryQueryAgent",
                                            "metadataSize", "(Ljava/nio/channels/FileChannel;I)J", false);
                                    return;
                                }
                                if ((method.equals("inventory") || method.equals("inventoryFile"))
                                        && owner.equals("java/nio/file/Files") && called.equals("readAttributes"))
                                    owner = "me/cortex/voxy/client/lod/InventoryQueryAgent";
                                super.visitMethodInsn(opcode, owner, called, descriptor, isInterface);
                            }
                        };
                    }
                }, 0);
                return writer.toByteArray();
            }
        });
    }
    public static long metadataSize(java.nio.channels.FileChannel channel, int reader) throws IOException {
        metadataQueries.get()[reader]++;
        long extent = channel.size();
        Runnable action = afterMetadataSize.get();
        if (action != null) { afterMetadataSize.remove(); action.run(); }
        return extent;
    }
    public static <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        reads++;
        return Files.readAttributes(path, type, options);
    }
    public static void begin() {
        reads = 0; elapsedNanos = 0;
        allocated = BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId()); started = System.nanoTime();
    }
    public static void end() {
        heapBytes = BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
        elapsedNanos = System.nanoTime() - started;
    }
}
