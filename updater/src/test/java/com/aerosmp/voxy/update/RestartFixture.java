package com.aerosmp.voxy.update;

import java.nio.file.*;

/** Real standalone Java process used to verify the updater restart lifecycle. */
public final class RestartFixture {
    public static void main(String[] args) throws Exception {
        Path game=Path.of(args[0]); Path marker=game.resolve("restart-requested");
        if (Files.exists(marker)) {
            Files.writeString(game.resolve("relaunched.pid"),Long.toString(ProcessHandle.current().pid()));
            Thread.sleep(90_000); return;
        }
        Files.writeString(marker,"requested");
        RestartHelper.prepare(new AutoUpdater.Update(game,game.resolve("mods/voxy-rewrite-client-0.2.0-debug.jar"),
                game.resolve(".voxy-rewrite-updater/new.jar.part"),game.resolve("mods/voxy-rewrite-client-0.3.0-beta-debug.jar"),BuildInfo.BUILD,args[1]));
        // Find only this fixture's direct helper child, without recording launch arguments.
        ProcessHandle.current().children().findFirst().ifPresent(helper -> {
            try { Files.writeString(game.resolve("helper.pid"),Long.toString(helper.pid())); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
    }
}
