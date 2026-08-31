package me.cortex.voxy.bridge;

import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.minecraft.server.level.ServerPlayer;

/** Stable no-op API used by production builds; the debug JAR replaces this class. */
final class ServerDebug {
    private ServerDebug() {}

    static void register(PayloadRegistrar registrar) {}
    static void serverStart(byte transport) {}
    static void serverStop() {}
    static void playerLogout(ServerPlayer player) {}
    static void sessionOpened(ServerPlayer player, Object session) {}
    static void sessionClosed(ServerPlayer player, Object session) {}
    static void fromClient(ServerPlayer player, Object session, int bytes) {}
    static void dequeued(ServerPlayer player, Object session) {}
    static void toClient(ServerPlayer player, Object session, int bytes) {}
}
