package me.cortex.voxy.server;

import java.io.InputStream;
import java.io.OutputStream;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Posts real NeoForge lifecycle events against production listeners with an owned fake child. */
public final class ServerLifecycleBehaviorTest {
    public static void main(String[] args) throws Exception {
        new VoxyServer(BusBuilder.builder().build());
        NeoForge.EVENT_BUS.start();
        for (boolean crashed : new boolean[]{false, true}) {
            var child = new Child();
            set(RustBackend.class, "running", true);
            set(RustBackend.class, "process", child);
            set(VoxyServer.class, "accepting", true);
            if (!crashed) NeoForge.EVENT_BUS.post(new ServerStoppingEvent(null));
            NeoForge.EVENT_BUS.post(new ServerStoppedEvent(null));
            NeoForge.EVENT_BUS.post(new ServerStoppedEvent(null));
            check(!child.isAlive() && child.destroyed == 1,
                    "crash=" + crashed + " cleanup destroyed child " + child.destroyed + " times");
            check(!(boolean)get(RustBackend.class, "running"), "backend would restart after server exit");
            check(get(RustBackend.class, "process") == null, "dead process retained");
            check(!(boolean)get(VoxyServer.class, "accepting"), "endpoint advertised after server exit");
        }
        System.out.println("normal and crash-only server lifecycle cleanup passed");
    }

    private static void set(Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }
    private static Object get(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final class Child extends Process {
        int destroyed;
        @Override public boolean isAlive() { return destroyed == 0; }
        @Override public void destroy() { destroyed++; }
        @Override public int waitFor() { check(!isAlive(), "wait before termination"); return 0; }
        @Override public int exitValue() { if (isAlive()) throw new IllegalThreadStateException(); return 0; }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
    }
}
