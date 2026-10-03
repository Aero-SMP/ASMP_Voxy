package com.aerosmp.voxy.update;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import java.util.concurrent.TimeUnit;

public final class UpdaterBehaviorTest {
    public static void main(String[] args) throws Exception {
        Path state=Path.of(".verification").toAbsolutePath(); Files.createDirectories(state);
        Path root=Files.createTempDirectory(state,"updater-");
        Path game=root.resolve("game"); Files.createDirectories(game.resolve("mods")); Files.createDirectories(game.resolve(".voxy-rewrite-updater"));
        Path staged=root.resolve("valid.jar"); fixture(staged,"client",250);
        String hash=hash(staged);
        AutoUpdater.verify(staged,"client",250,hash);
        rejects(() -> AutoUpdater.verify(staged,"server",250,hash));
        rejects(() -> AutoUpdater.verify(staged,"client",251,hash));
        rejects(() -> AutoUpdater.verify(staged,"client",250,"0".repeat(64)));
        rejects(() -> AutoUpdater.manifest("build=250\nfile=../evil.jar\nsha256="+hash));
        rejects(() -> AutoUpdater.manifest("build=-1\nfile=voxy-rewrite-client-0.3.1-debug.jar\nsha256="+hash));
        rejects(() -> AutoUpdater.manifest("x".repeat(4097)));
        Path old=game.resolve("mods/voxy-rewrite-client-0.3.0-debug.jar"), target=game.resolve("mods/voxy-rewrite-client-0.3.1-debug.jar");
        Path unrelated=game.resolve("mods/unrelated.jar"); Files.writeString(unrelated,"keep"); Files.writeString(old,"old");
        rejects(() -> AutoUpdater.install(new AutoUpdater.Update(game,old,root.resolve("missing.jar"),target,250,hash)));
        check(Files.readString(old).equals("old"),"failed install did not restore old jar");
        AutoUpdater.install(new AutoUpdater.Update(game,old,staged,target,250,hash));
        check(!Files.exists(old) && hash(target).equals(hash),"replacement failed");
        check(Files.readString(unrelated).equals("keep"),"unrelated mod was changed");
        check(Files.readString(game.resolve(".voxy-rewrite-updater/"+old.getFileName()+".backup")).equals("old"),"backup missing");
        check(RestartHelper.parseWindows("\"C:\\Program Files\\Java\\javaw.exe\" -cp \"C:\\a b\\c.jar\" Main \"\" \"x\\\"y\"")
                .equals(List.of("C:\\Program Files\\Java\\javaw.exe","-cp","C:\\a b\\c.jar","Main","","x\"y")),"Windows launch arguments changed");
        check(RestartHelper.parseWindows("java C:\\directory\\ --flag").equals(List.of("java","C:\\directory\\","--flag")),"unquoted trailing backslash consumed the next argument");
        Path launch=root.resolve("launch"); Files.createDirectories(launch);
        Path agent=root.resolve("agent.jar"); Files.writeString(agent,"agent");
        Path arguments=root.resolve("arguments.txt"); Files.writeString(arguments,"-Xmx256m");
        List<String> command=RestartHelper.stabilize(List.of("java","@"+arguments,"-javaagent:"+agent+"=flags",
                "com.modrinth.theseus.MinecraftLaunch","MinecraftMain","--gameDir","wrong","split","path","--username","MGengine"),launch,game);
        check(command.contains("MinecraftMain") && !command.contains("com.modrinth.theseus.MinecraftLaunch"),"Modrinth wrapper was not unwrapped");
        check(command.get(command.indexOf("--gameDir")+1).equals(game.toString()) && !command.contains("split"),"game directory was not canonicalized");
        check(Files.exists(Path.of(command.get(1).substring(1))),"argument file not stabilized");
        restartFixture(root);
        System.out.println("Verified SHA/build/side rejection, manifest bounds, atomic rollback, exact mod scope, Windows arguments, launch-file preservation, and independent client restart.");
    }
    private static void restartFixture(Path root) throws Exception {
        Path game=root.resolve("restart game"); Files.createDirectories(game.resolve("mods"));
        Path client=Path.of("client/build/libs/voxy-rewrite-client-0.3.0-beta-debug.jar").toAbsolutePath();
        Path old=game.resolve("mods/voxy-rewrite-client-0.2.0-debug.jar"); Files.copy(client,old);
        Files.createDirectories(game.resolve(".voxy-rewrite-updater"));
        Path staged=game.resolve(".voxy-rewrite-updater/new.jar.part"); Files.copy(client,staged);
        Path current=Path.of("updater/build/classes/java/main").toAbsolutePath();
        Path tests=Path.of("updater/build/classes/java/test").toAbsolutePath();
        Process original=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",current+File.pathSeparator+tests,RestartFixture.class.getName(),game.toString(),hash(staged))
                .redirectErrorStream(true).redirectOutput(root.resolve("fixture.log").toFile()).start();
        ProcessHandle helper=null, relaunched=null;
        try {
            check(original.waitFor(15,TimeUnit.SECONDS) && original.exitValue()==0,"restart fixture failed to hand off");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(55);
            while(System.nanoTime()<deadline) {
                Path marker=game.resolve("relaunched.pid");
                if(Files.exists(marker)) relaunched=ProcessHandle.of(Long.parseLong(Files.readString(marker))).orElse(null);
                Path helperPid=game.resolve("helper.pid");
                if(Files.exists(helperPid)) helper=ProcessHandle.of(Long.parseLong(Files.readString(helperPid))).orElse(null);
                Path log=game.resolve(".voxy-rewrite-updater/restart.log");
                if(Files.exists(log) && Files.readString(log).contains("client alive after 45 seconds")) break;
                Thread.sleep(50);
            }
            check(relaunched!=null && relaunched.isAlive(),"restarted Java fixture is not alive");
            check(!Files.exists(old) && Files.exists(game.resolve("mods/voxy-rewrite-client-0.3.0-beta-debug.jar")),"restart did not replace exactly the old jar");
            check(Files.readString(game.resolve(".voxy-rewrite-updater/restart.log")).contains("client alive after 45 seconds"),"restart startup observation did not finish");
        } finally {
            if(helper!=null && helper.isAlive()) helper.destroyForcibly();
            if(relaunched!=null && relaunched.isAlive()) relaunched.destroyForcibly();
            if(original.isAlive()) original.destroyForcibly();
        }
    }
    private static void fixture(Path path,String side,long build) throws Exception {
        Manifest manifest=new Manifest(); var attrs=manifest.getMainAttributes(); attrs.putValue("Manifest-Version","1.0");
        attrs.putValue("Voxy-Update-Side",side); attrs.putValue("Voxy-Update-Build",Long.toString(build));
        try(JarOutputStream jar=new JarOutputStream(Files.newOutputStream(path),manifest)) {
            jar.putNextEntry(new JarEntry("com/aerosmp/voxy/update/RestartHelper.class"));jar.write(0);jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/neoforge.mods.toml"));jar.write("modId=\"voxy\"".getBytes());jar.closeEntry();
        }
    }
    private static String hash(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action) throws Exception {
        try { action.run(); } catch(IOException expected) { return; } throw new AssertionError("invalid update was accepted");
    }
}
