package com.aerosmp.voxy.update;

import static com.aerosmp.voxy.update.AutoUpdater.*;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/** Watches the exact game identity and performs a content-verified launcher transaction. */
final class RestartHelper {
    static void run(String[] args) throws Exception {
        Path game = Path.of(args[0]),
                state = state(game),
                installed = game.resolve("mods/voxy-client-debug.jar");
        long parent = Long.parseLong(args[3]);
        Files.writeString(state.resolve("accepted"), args[1]);
        ProcessHandle.of(Long.parseLong(args[1]))
                .filter(
                        p ->
                                p.info()
                                        .startInstant()
                                        .map(Instant::toString)
                                        .orElse("")
                                        .equals(args[2]))
                .ifPresent(p -> p.onExit().join());
        Update update =
                new Update(
                        game,
                        installed,
                        state.resolve("voxy-client-debug.jar.part"),
                        "client",
                        args[4]);
        String previous = hash(installed);
        install(update);
        Files.deleteIfExists(state.resolve("ready"));
        Files.deleteIfExists(state.resolve("starting"));
        Desktop.getDesktop().browse(launcher(state));
        ProcessHandle launched = null;
        boolean announced = false;
        while (!Files.exists(state.resolve("ready"))) {
            if (!announced && Files.exists(state.resolve("starting"))) {
                String[] identity = Files.readString(state.resolve("starting")).split(" ");
                if (identity.length != 4
                        || !identity[2].equals(Long.toString(parent))
                        || !identity[3].equals(update.sha256()))
                    throw new IOException("Invalid startup identity");
                announced = true;
                launched =
                        ProcessHandle.of(Long.parseLong(identity[0]))
                                .filter(
                                        p ->
                                                p.info()
                                                        .startInstant()
                                                        .map(Instant::toString)
                                                        .orElse("")
                                                        .equals(identity[1]))
                                .orElse(null);
                System.out.println("Client starting " + String.join(" ", identity));
            }
            if (announced && (launched == null || !launched.isAlive())) {
                verify(state.resolve("previous.jar"), "client", previous);
                Files.copy(
                        state.resolve("previous.jar"),
                        installed,
                        StandardCopyOption.REPLACE_EXISTING);
                Desktop.getDesktop().browse(launcher(state));
                throw new IOException(
                        "Client failed before readiness; restored previous jar and requested"
                            + " launcher recovery");
            }
            Thread.sleep(100);
        }
        String[] readiness = Files.readString(state.resolve("ready")).split(" ");
        if (!readiness[1].equals(update.sha256())
                || !ProcessHandle.of(Long.parseLong(readiness[0]))
                        .filter(ProcessHandle::isAlive)
                        .isPresent()) throw new IOException("Invalid client readiness");
        System.out.println("Client readiness " + String.join(" ", readiness));
    }
}
