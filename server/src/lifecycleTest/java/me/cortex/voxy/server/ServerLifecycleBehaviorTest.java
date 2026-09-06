package me.cortex.voxy.server;

import net.neoforged.bus.api.BusBuilder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import static me.cortex.voxy.server.SupervisorRecoveryBehaviorTest.*;

/** Real NeoForge normal/crash-only stop events and the production supervisor regression matrix. */
public final class ServerLifecycleBehaviorTest {
    public static void main(String[] args) throws Exception {
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
}
