package com.aerosmp.voxy.server;

import com.aerosmp.voxy.network.Endpoint;
import com.aerosmp.voxy.terrain.SectionCodec;
import com.aerosmp.voxy.terrain.TerrainStore;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.*;

/** A single supervised native process. The optional testing launcher owns its external cap. */
final class RustBackend implements AutoCloseable {
    private volatile boolean closed;
    private volatile Process process;
    private volatile Endpoint endpoint;
    private final Thread supervisor;
    RustBackend(Path world) throws java.io.IOException {
        Path root = Path.of(".voxy-rewrite").toAbsolutePath();
        byte[] binary;
        try (var input = RustBackend.class.getResourceAsStream("/native/linux-x86_64/voxy-rewrite-server")) {
            if (input == null || !System.getProperty("os.name").equals("Linux")
                    || !Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")))
                throw new java.io.IOException("The server artifact requires Linux x86_64");
            binary = input.readAllBytes();
        }
        Path executable = root.resolve("bin").resolve(SectionCodec.hex(SectionCodec.hash(binary))).resolve("voxy-rewrite-server");
        if (!Files.isRegularFile(executable) || !Arrays.equals(SectionCodec.hash(Files.readAllBytes(executable)), SectionCodec.hash(binary)))
            TerrainStore.atomic(executable, binary);
        if (!executable.toFile().setExecutable(true, true)) throw new java.io.IOException("Cannot make backend executable");
        List<String> command = new ArrayList<>();
        String launcher = System.getProperty("voxy.rust.launcher", "");
        if (!launcher.isBlank()) command.add(launcher);
        command.addAll(List.of(executable.toString(), "--world", world.toAbsolutePath().normalize().toString(),
                "--data", root.resolve("server").toString(), "--listen",
                System.getProperty("voxy.rewrite.listen", "0.0.0.0:25787")));
        supervisor = new Thread(() -> run(command), "Voxy native supervisor");
        supervisor.setDaemon(true); supervisor.start();
    }
    Endpoint endpoint() { return endpoint; }
    private void run(List<String> command) {
        while (!closed) {
            try {
                Process child = new ProcessBuilder(command).redirectErrorStream(true).start(); process = child;
                if (closed) { child.destroy(); return; }
                try (var lines = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                    for (String line; (line = lines.readLine()) != null;) {
                        System.out.println("[Voxy native] " + line);
                        if (line.startsWith("VOXY_READY ")) {
                            Map<String, String> values = new HashMap<>();
                            for (String item : line.substring(11).split(" ")) {
                                String[] pair = item.split("=", 2); if (pair.length == 2) values.put(pair[0], pair[1]);
                            }
                            if (!"voxy-rewrite-1".equals(values.get("alpn"))) throw new java.io.IOException("Wrong native protocol");
                            endpoint = new Endpoint(System.getProperty("voxy.rewrite.host", ""),
                                    Integer.parseInt(values.get("udp_port")), HexFormat.of().parseHex(values.get("cert_sha256")));
                        }
                    }
                }
                System.out.println("[Voxy native] exit=" + child.waitFor());
            } catch (Exception failure) { if (!closed) System.err.println("Voxy backend: " + failure); }
            finally {
                endpoint = null;
                Process child = process; if (child != null && child.isAlive()) child.destroy();
                process = null;
            }
            try { Thread.sleep(5000); } catch (InterruptedException stop) { return; }
        }
    }
    @Override public void close() {
        closed = true; endpoint = null; supervisor.interrupt();
        Process child = process;
        if (child != null) {
            child.destroy();
            Thread cleanup = new Thread(() -> {
                try { if (!child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) child.destroyForcibly(); }
                catch (InterruptedException stopped) { child.destroyForcibly(); }
            }, "Voxy native shutdown");
            cleanup.setDaemon(true); cleanup.start();
        }
    }
}
