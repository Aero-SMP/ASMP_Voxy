package com.aerosmp.voxy.update;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Independent client restart helper: wait for exit, replace one jar, replay Java launch. */
public final class RestartHelper {
    private RestartHelper() {}
    public static void prepare(AutoUpdater.Update update) throws Exception {
        Path state = update.game().resolve(".voxy-rewrite-updater");
        Path launch = state.resolve("launch-" + UUID.randomUUID()); Files.createDirectories(launch); restrict(launch, "rwx------");
        List<String> command = stabilize(capture(), launch, update.game());
        Path helper = launch.resolve("helper.jar"); Files.copy(update.staged(), helper);
        Path plan = launch.resolve("restart.bin");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(plan))) {
            out.writeLong(ProcessHandle.current().pid());
            out.writeUTF(ProcessHandle.current().info().startInstant().map(Instant::toString).orElse(""));
            for (Path path : List.of(update.game(), update.current(), update.staged(), update.target())) out.writeUTF(path.toString());
            out.writeLong(update.build()); out.writeUTF(update.sha256()); out.writeInt(command.size());
            for (String arg : command) out.writeUTF(arg);
        }
        restrict(plan, "rw-------");
        Process process = new ProcessBuilder(command.getFirst(), "-Xmx64m", "-cp", helper.toString(),
                RestartHelper.class.getName(), plan.toString()).directory(update.game().toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(state.resolve("restart.log").toFile())).start();
        process.getOutputStream().close();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Files.exists(plan) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
        if (Files.exists(plan) || !process.isAlive()) { process.destroyForcibly(); throw new IOException("restart helper did not accept plan"); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IOException("expected restart plan");
        Path plan = Path.of(args[0]); long pid; String identity; AutoUpdater.Update update; List<String> command = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(plan))) {
            pid = in.readLong(); identity = in.readUTF();
            update = new AutoUpdater.Update(Path.of(in.readUTF()), Path.of(in.readUTF()), Path.of(in.readUTF()),
                    Path.of(in.readUTF()), in.readLong(), in.readUTF());
            int count = in.readInt(); if (count < 1 || count > 4096) throw new IOException("invalid launch argument count");
            for (int i=0; i<count; i++) command.add(in.readUTF());
        }
        Files.delete(plan);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (sameProcess(pid, identity)) {
            if (System.nanoTime() > deadline) throw new IOException("old client is still alive; update aborted");
            Thread.sleep(100);
        }
        AutoUpdater.verify(update.staged(), "client", update.build(), update.sha256());
        AutoUpdater.install(update);
        Process launched = new ProcessBuilder(command).directory(update.game().toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(update.game().resolve(".voxy-rewrite-updater/relaunched-java.log").toFile())).start();
        launched.getOutputStream().close();
        System.out.println(Instant.now() + " restarted client pid=" + launched.pid() + " build=" + update.build());
        if (launched.waitFor(45, TimeUnit.SECONDS)) {
            Path backup = update.game().resolve(".voxy-rewrite-updater").resolve(update.current().getFileName()+".backup");
            Files.delete(update.target()); Files.move(backup, update.current(), StandardCopyOption.REPLACE_EXISTING);
            Process restored = new ProcessBuilder(command).directory(update.game().toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(update.game().resolve(".voxy-rewrite-updater/relaunched-java.log").toFile())).start();
            restored.getOutputStream().close();
            throw new IOException("new client exited; restored previous jar and relaunched pid=" + restored.pid());
        }
        System.out.println(Instant.now() + " client alive after 45 seconds");
    }
    private static boolean sameProcess(long pid, String identity) {
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).filter(process -> identity.isEmpty()
                || process.info().startInstant().map(Instant::toString).orElse("").equals(identity)).isPresent();
    }
    private static List<String> capture() throws Exception {
        var info = ProcessHandle.current().info(); List<String> command = new ArrayList<>();
        if (info.arguments().isPresent()) {
            command.add(info.command().orElseThrow()); command.addAll(List.of(info.arguments().get()));
        } else {
            // Runtime-owned metadata survives mods masking the operating-system command line.
            String application = System.getProperty("sun.java.command", "");
            String classpath = System.getProperty("java.class.path", "");
            if (application.isBlank() || classpath.isBlank()) throw new IOException("Java launch metadata unavailable");
            command.add(info.command().orElseThrow());
            command.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
            command.add("-cp"); command.add(classpath);
            command.addAll(parseWindows(application));
        }
        if (command.contains("org.prismlauncher.EntryPoint")) throw new IOException("Prism restart support is not initialized");
        int wrapper = command.indexOf("com.modrinth.theseus.MinecraftLaunch");
        String exact = System.getProperty("modrinth.process.args");
        if (wrapper >= 0 && exact != null) {
            command.subList(wrapper+2, command.size()).clear(); command.addAll(List.of(exact.split("\u001f", -1)));
        }
        return command;
    }
    static List<String> stabilize(List<String> original, Path launch, Path game) throws IOException {
        ArrayList<String> command = new ArrayList<>(original);
        for (int i=1; i<command.size(); i++) {
            String arg = command.get(i); String prefix; String file; String suffix = "";
            if (arg.startsWith("@") && !arg.startsWith("@@")) { prefix="@"; file=arg.substring(1); }
            else if (arg.startsWith("-javaagent:")) {
                prefix="-javaagent:"; file=arg.substring(prefix.length()); int equals=file.indexOf('=');
                if (equals>=0) { suffix=file.substring(equals); file=file.substring(0,equals); }
                Path candidate=Path.of(file); if (!candidate.isAbsolute()) candidate=game.resolve(candidate);
                if (!Files.isRegularFile(candidate)) { command.remove(i--); continue; }
            } else continue;
            Path source = Path.of(file); if (!source.isAbsolute()) source=game.resolve(source);
            Path copy=launch.resolve("argument-"+i+(prefix.equals("@") ? ".txt" : ".jar"));
            Files.copy(source,copy); restrict(copy,"rw-------"); command.set(i,prefix+copy+suffix);
        }
        int wrapper=command.indexOf("com.modrinth.theseus.MinecraftLaunch");
        if (wrapper>=0) {
            if (wrapper+1>=command.size()) throw new IOException("missing Minecraft main class");
            command.set(wrapper, command.remove(wrapper+1));
        }
        int gameOption=command.indexOf("--gameDir");
        if (gameOption>=0) {
            if (gameOption+1>=command.size()) throw new IOException("missing game directory");
            int end=gameOption+2; while(end<command.size() && !command.get(end).startsWith("--")) end++;
            command.subList(gameOption+2,end).clear(); command.set(gameOption+1,game.toString());
        }
        return command;
    }
    static List<String> parseWindows(String value) throws IOException {
        List<String> result=new ArrayList<>(); int cursor=0;
        while (cursor<value.length()) {
            while(cursor<value.length() && Character.isWhitespace(value.charAt(cursor))) cursor++;
            if(cursor==value.length()) break;
            StringBuilder argument=new StringBuilder(); boolean quoted=false;
            while(cursor<value.length() && (quoted || !Character.isWhitespace(value.charAt(cursor)))) {
                int slashes=0; while(cursor<value.length() && value.charAt(cursor)=='\\') { slashes++; cursor++; }
                if(cursor<value.length() && value.charAt(cursor)=='"') {
                    argument.append("\\".repeat(slashes/2)); if(slashes%2==0) quoted=!quoted; else argument.append('"'); cursor++;
                } else {
                    argument.append("\\".repeat(slashes));
                    if(cursor<value.length() && (quoted || !Character.isWhitespace(value.charAt(cursor)))) argument.append(value.charAt(cursor++));
                    else break;
                }
            }
            if(quoted) throw new IOException("unterminated Java launch quote"); result.add(argument.toString());
        }
        return result;
    }
    private static void restrict(Path path, String permissions) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    }
}
