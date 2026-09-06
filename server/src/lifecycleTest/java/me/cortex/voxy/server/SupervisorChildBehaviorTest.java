package me.cortex.voxy.server;

import java.nio.file.Files;
import java.nio.file.Path;

/** Isolated real child: exceed pipe capacity, prove drain, then accept an explicit exit marker. */
public final class SupervisorChildBehaviorTest {
    public static void main(String[] args) throws Exception {
        System.out.println(SupervisorRecoveryBehaviorTest.READY);
        for (int i = 0; i < 10000; i++) System.out.println("ordinary output after logger degradation " + i);
        System.out.flush();
        Files.writeString(Path.of(args[0]), "drained");
        System.in.read();
        System.exit(23);
    }
}
