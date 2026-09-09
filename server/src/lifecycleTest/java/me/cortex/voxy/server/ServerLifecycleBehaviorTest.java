package me.cortex.voxy.server;

import net.neoforged.bus.api.BusBuilder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import static me.cortex.voxy.server.SupervisorRecoveryBehaviorTest.*;

/** Real NeoForge normal/crash-only stop events and the production supervisor regression matrix. */
public final class ServerLifecycleBehaviorTest {
    public static void main(String[] args) throws Exception {
        defaultConfiguration();
        advertisedAddresses();
        new VoxyServer(BusBuilder.builder().build());
        NeoForge.EVENT_BUS.start();
        SupervisorRecoveryBehaviorTest.run();
        for (boolean crashed : new boolean[]{false, true}) {
            var child = new Child();
            var owned = owner(child);
            ready(owned, child);
            var field = VoxyServer.class.getDeclaredField("accepting"); field.setAccessible(true); field.set(null, true);
            if (!crashed) NeoForge.EVENT_BUS.post(new ServerStoppingEvent(null));
            NeoForge.EVENT_BUS.post(new ServerStoppedEvent(null));
            NeoForge.EVENT_BUS.post(new ServerStoppedEvent(null));
            check(!child.isAlive() && child.destroyed.get() == 1,
                    "crash=" + crashed + " cleanup destroyed child " + child.destroyed + " times");
            check(!owned.wanted && !owned.thread.isAlive() && owned.child == null, "backend survives server stop");
            check(!(boolean) field.get(null), "endpoint advertised after server exit");
        }
        System.out.println("normal and crash-only server lifecycle cleanup passed; assertions=" + assertions.get());
    }

    private static void advertisedAddresses() {
        String[][] cases = {
                {"", "", "0"}, {"lod.example.com", "lod.example.com", "0"},
                {":30000", "", "30000"}, {"LOD.example.com:30000", "lod.example.com", "30000"},
                {"127.0.0.1:1", "127.0.0.1", "1"}, {"127.0.0.1", "127.0.0.1", "0"},
                {"[2001:db8::1]:65535", "2001:db8::1", "65535"}, {"[::1]", "::1", "0"},
                {"bücher.example:30000", "xn--bcher-kva.example", "30000"}
        };
        for (String[] entry : cases) {
            var address = AdvertisedAddress.parse(entry[0]);
            check(address.host().equals(entry[1]) && address.udpPortOverride() == Integer.parseInt(entry[2]),
                    "incorrect advertised address: " + entry[0]);
        }
        for (String invalid : new String[]{null, " ", " host", "host ", "host:", ":", ":0", ":65536",
                ":-1", ":+1", ":１２", ":999999999999", "host:abc", "host:1:2", "2001:db8::1",
                "[::1", "[::1]oops", "[::1]:", "[::1]:0", "[::1]:65536", "[host]:123", "[]",
                "[fe80::1%eth0]:123", "https://host:123", "host/path", "host..example", "-host"}) {
            boolean rejected = false;
            try { AdvertisedAddress.parse(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "invalid advertised address accepted: " + invalid);
        }
    }

    private static void defaultConfiguration() throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("voxy-default-config-");
        var file = directory.resolve("voxy-rust.toml");
        try {
            RustBackend.ensureConfig(file);
            String generated = java.nio.file.Files.readString(file);
            var config = new com.electronwill.nightconfig.toml.TomlParser().parse(generated);
            for (String removed : new String[]{"world", "data", "dimension", "poll_ms"}) {
                check(!config.contains(removed), "removed setting remains in generated config: " + removed);
            }
            check("".equals(config.get("quic.listen")), "automatic listener must remain empty");
            check("".equals(config.get("quic.advertise")), "automatic advertisement must remain empty");
            check(!config.contains("quic.advertise_host") && !config.contains("quic.advertise_port"),
                    "legacy advertised fields remain in generated config");
            check(RustBackend.minecraftPort(file) == 25565, "wrong missing-file fallback port");
            check(config.getInt("rayon_threads") == 0, "wrong worker default");
            RustBackend.ensureConfig(file);
            check(generated.equals(java.nio.file.Files.readString(file)), "second startup rewrote config");
            var properties = directory.resolve("server.properties");
            for (String value : new String[]{"25582", "1", "65535"}) {
                java.nio.file.Files.writeString(properties, "server-port=" + value + "\n");
                RustBackend.ensureConfig(file);
                check(RustBackend.minecraftPort(file) == Integer.parseInt(value), "ignored current server-port");
                check(generated.equals(java.nio.file.Files.readString(file)), "persisted a resolved port");
            }
            java.nio.file.Files.writeString(properties, "server-port=25583\n");
            RustBackend.ensureConfig(file);
            check(RustBackend.minecraftPort(file) == 25583 && generated.equals(java.nio.file.Files.readString(file)),
                    "port change was not observed without modifying the TOML");
            for (String value : new String[]{"0", "65536", "-1", "abc", ""}) {
                java.nio.file.Files.writeString(properties, "server-port=" + value + "\n");
                boolean rejected = false;
                try { RustBackend.minecraftPort(file); } catch (java.io.IOException expected) { rejected = true; }
                check(rejected && generated.equals(java.nio.file.Files.readString(file)), "invalid server-port modified configuration");
            }
            java.nio.file.Files.writeString(properties, "# no server-port\nlevel-name=world\n");
            RustBackend.ensureConfig(file);
            check(RustBackend.minecraftPort(file) == 25565, "missing property did not use Minecraft default");
            for (String existing : new String[]{"# custom\nworld = \"other-world\"\n", "invalid toml [[", ""}) {
                java.nio.file.Files.writeString(file, existing);
                RustBackend.ensureConfig(file);
                check(existing.equals(java.nio.file.Files.readString(file)), "overwrote existing configuration");
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file);
            java.nio.file.Files.deleteIfExists(directory.resolve("server.properties"));
            java.nio.file.Files.delete(directory);
        }
    }
}
