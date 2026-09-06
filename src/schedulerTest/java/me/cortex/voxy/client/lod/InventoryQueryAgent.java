package me.cortex.voxy.client.lod;

import java.lang.instrument.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

/** Optional test agent: measures explicit inventory calls, not Files.walk's internal queries. */
public final class InventoryQueryAgent {
    static volatile boolean active;
    static volatile long reads, heapBytes, elapsedNanos;
    private static long started, allocated;
    private static final com.sun.management.ThreadMXBean BEAN =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();

    public static void premain(String args, Instrumentation instrumentation) {
        active = true;
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] bytes) {
                if (!name.equals("me/cortex/voxy/client/lod/RegionalDiskBudget")) return null;
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
