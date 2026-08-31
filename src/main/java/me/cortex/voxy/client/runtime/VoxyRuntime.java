package me.cortex.voxy.client.runtime;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.mixin.sodium.AccessorSodiumWorldRenderer;
import me.cortex.voxy.client.world.WorldIdentifier;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.storage.SectionStorage;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.thread.UnifiedServiceThreadPool;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.service.SectionSavingService;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.locks.StampedLock;
import java.util.zip.CRC32C;

//TODO: add thread access verification (I.E. only accessible on a single thread)
public final class VoxyRuntime {
    private static final long SERVER_ID_MAGIC = 0x5658595345525633L; // VXYSERV3
    private volatile boolean isRunning = true;
    private final Thread worldCleaner;
    private final Path basePath;
    protected final UnifiedServiceThreadPool threadPool;
    protected final SectionSavingService savingService;

    private final StampedLock activeWorldLock = new StampedLock();
    private final HashMap<WorldIdentifier, WorldEngine> activeWorlds = new HashMap<>();

    public VoxyRuntime() {
        Logger.info("Initializing voxy instance");
        this.basePath = getBasePath().normalize();
        this.threadPool = new UnifiedServiceThreadPool();
        this.savingService = new SectionSavingService(this.getServiceManager());
        this.worldCleaner = new Thread(()->{
            try {
                while (this.isRunning) {
                    //noinspection BusyWait
                    Thread.sleep(1000);
                    this.cleanIdle();
                }
            } catch (InterruptedException e) {
                //We are exiting, so just exit
            } catch (Exception e) {
                Logger.error("Exception in world cleaner",e);
            }
        });
        this.worldCleaner.setPriority(Thread.MIN_PRIORITY);
        this.worldCleaner.setName("Active world cleaner");
        this.worldCleaner.setDaemon(true);
        this.worldCleaner.start();
        this.updateDedicatedThreads();
    }

    protected void setNumThreads(int threads) {
        if (threads<0) throw new IllegalArgumentException("Num threads <0");
        if (this.threadPool.setNumThreads(threads)) {
            Logger.info("Dedicated voxy thread pool size: " + threads);
        }
    }

    public void updateDedicatedThreads() {
        int target = VoxyConfig.CONFIG.serviceThreads;
        if (!VoxyConfig.CONFIG.dontUseSodiumBuilderThreads) {
            var renderer = SodiumWorldRenderer.instanceNullable();
            if (renderer != null) {
                var manager = ((AccessorSodiumWorldRenderer) renderer).getRenderSectionManager();
                if (manager != null) {
                    this.setNumThreads(Math.max(1, target - manager.getBuilder().getTotalThreadCount()));
                    return;
                }
            }
        }
        this.setNumThreads(target);
    }

    public ServiceManager getServiceManager() {
        return this.threadPool.serviceManager;
    }
    public UnifiedServiceThreadPool getThreadPool() {
        return this.threadPool;
    }
    //TODO: reference count the world object
    // have automatic world cleanup after ~1 minute of inactivity and the reference count equaling zero possibly
    // note, the reference count should be separate from the number of active chunks to prevent many issues
    // a world is no longer active once it has no reference counts and no active chunks associated with it
    public WorldEngine getNullable(WorldIdentifier identifier) {
        if (!this.isRunning) return null;
        long stamp = this.activeWorldLock.readLock();
        try {
            WorldEngine world = this.activeWorlds.get(identifier);
            if (world != null) {
                // Keep the world protected from the cleaner until it is marked active.
                world.markActive();
            }
            return world;
        } finally {
            this.activeWorldLock.unlockRead(stamp);
        }
    }

    public WorldEngine getOrCreate(WorldIdentifier identifier) {
        if (!this.isRunning) {
            Logger.error("Tried getting world object on voxy instance but its not running");
            return null;
        }
        var world = this.getNullable(identifier);
        if (world != null) {
            return world;
        }
        long stamp = this.activeWorldLock.writeLock();
        try {
            if (!this.isRunning) {
                Logger.error("Tried getting world object on voxy instance but its not running");
                return null;
            }
            world = this.activeWorlds.get(identifier);
            if (world == null) world = this.createWorld(identifier);
            world.markActive();
            return world;
        } finally {
            this.activeWorldLock.unlockWrite(stamp);
        }
    }


    private SectionStorage createStorage(WorldIdentifier identifier) {
        return new SectionStorage(this.basePath.resolve(identifier.getWorldId()).resolve("storage-v3-log"));
    }

    private WorldEngine createWorld(WorldIdentifier identifier) {
        if (!this.isRunning) {
            throw new IllegalStateException("Cannot create world while not running");
        }
        if (this.activeWorlds.containsKey(identifier)) {
            throw new IllegalStateException("Existing world with identifier");
        }
        Logger.info("Creating new world engine: " + identifier.getLongHash() + "@" + System.identityHashCode(this));
        var world = new WorldEngine(this.createStorage(identifier));
        world.setSaveCallback(this.savingService::enqueueSave);
        this.activeWorlds.put(identifier, world);
        return world;
    }

    public void cleanIdle() {
        List<WorldIdentifier> idleWorlds = null;
        {
            long stamp = this.activeWorldLock.readLock();
            for (var pair : this.activeWorlds.entrySet()) {
                if (pair.getValue().isWorldIdle()) {
                    if (idleWorlds == null) idleWorlds = new ArrayList<>();
                    idleWorlds.add(pair.getKey());
                }
            }
            this.activeWorldLock.unlockRead(stamp);
        }

        if (idleWorlds != null) {
            //Shutdown and clear all idle worlds
            long stamp = this.activeWorldLock.writeLock();
            for (var id : idleWorlds) {
                var world = this.activeWorlds.remove(id);
                if (world == null) continue;//Race condition between unlock read and acquire write
                if (!world.isWorldIdle()) {this.activeWorlds.put(id, world); continue;}//No longer idle
                Logger.info("Shutting down idle world: " + id.getLongHash());
                //If is here close and free the world
                world.free();
            }
            this.activeWorldLock.unlockWrite(stamp);
        }
    }

    public void shutdown() {
        Logger.info("Shutting down voxy instance");
        this.isRunning = false;
        try {
            this.worldCleaner.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        this.cleanIdle();

        try {this.savingService.shutdown();} catch (Exception e) {Logger.error(e);}


        long stamp = this.activeWorldLock.writeLock();

        if (!this.activeWorlds.isEmpty()) {
            boolean printedNotice = false;
            for (var world : new ArrayList<>(this.activeWorlds.values())) {
                if (world.isWorldUsed()) {
                    if (!printedNotice) {
                        printedNotice = true;
                        Logger.error("Not all worlds shutdown, force closing worlds");
                    }
                    //Dont lock in the loopy thing, this should basicly never happen if it does something horrific happened
                    this.activeWorldLock.unlockWrite(stamp);
                    while (world.isWorldUsed()) {
                        try {
                            //noinspection BusyWait
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    stamp = this.activeWorldLock.writeLock();
                }
                //Free the world
                world.free();
            }
            this.activeWorlds.clear();
        }

        try {this.threadPool.shutdown();} catch (Exception e) {Logger.error(e);}

        if (!this.activeWorlds.isEmpty()) {
            throw new IllegalStateException("Not all worlds shutdown");
        }
        Logger.info("Instance shutdown");
        this.activeWorldLock.unlockWrite(stamp);
        RenderResourceReuse.clearResources();
    }

    public boolean isRunning() {
        return this.isRunning;
    }

    public static Path getStoragePath(WorldIdentifier identifier) {
        return getBasePath().normalize().resolve(identifier.getWorldId()).resolve("storage-v3-log");
    }

    public static boolean serverCacheNeedsReset(WorldIdentifier identifier, long serverId) {
        Path root = getStoragePath(identifier);
        Path marker = root.resolve("server-id.vxy");
        try {
            if (Files.exists(marker)) return readServerId(marker) != serverId;
            Path sections = root.resolve("sections");
            if (!Files.isDirectory(sections)) return false;
            try (var files = Files.list(sections)) {
                return files.anyMatch(path -> path.getFileName().toString().endsWith(".vxl"));
            }
        } catch (IOException | RuntimeException e) {
            Logger.warn("Voxy server identity is unreadable; resetting only cached sections", e);
            return true;
        }
    }

    /** Called only after the renderer and Voxy instance have released this world's storage. */
    public static void resetServerCache(WorldIdentifier identifier, long serverId) {
        Path root = getStoragePath(identifier);
        Path sections = root.resolve("sections");
        try {
            Files.createDirectories(sections);
            try (var files = Files.list(sections)) {
                for (Path path : files.toList()) {
                    String name = path.getFileName().toString();
                    if (Files.isRegularFile(path) && (name.endsWith(".vxl") || name.endsWith(".idx")
                            || name.endsWith(".tmp") || name.endsWith(".compact"))) Files.deleteIfExists(path);
                }
            }
            forceDirectory(sections);
            writeServerId(root.resolve("server-id.vxy"), serverId);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to reset stale Voxy section cache " + root, e);
        }
    }

    public static void recordServerIdentity(WorldIdentifier identifier, long serverId) {
        Path marker = getStoragePath(identifier).resolve("server-id.vxy");
        try {
            if (!Files.exists(marker) || readServerId(marker) != serverId) writeServerId(marker, serverId);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to persist Voxy server identity " + marker, e);
        }
    }

    private static long readServerId(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != 20) throw new IOException("invalid Voxy server identity length");
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long magic = data.getLong();
        long id = data.getLong();
        int expected = data.getInt();
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, 16);
        if (magic != SERVER_ID_MAGIC || (int) crc.getValue() != expected) {
            throw new IOException("invalid Voxy server identity checksum");
        }
        return id;
    }

    private static void writeServerId(Path path, long serverId) throws IOException {
        Files.createDirectories(path.getParent());
        ByteBuffer data = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        data.putLong(SERVER_ID_MAGIC).putLong(serverId);
        CRC32C crc = new CRC32C();
        crc.update(data.array(), 0, 16);
        data.putInt((int) crc.getValue()).flip();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            while (data.hasRemaining()) output.write(data);
            output.force(false);
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
        forceDirectory(path.getParent());
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Some client filesystems do not expose directory fsync; atomic replacement still
            // prevents a partially written identity marker.
        }
    }

    private static Path getBasePath() {
        Path basePath = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy").resolve("saves");
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            basePath = server.getWorldPath(LevelResource.ROOT).resolve("voxy");
        } else {
            var gameMode = Minecraft.getInstance().gameMode;
            if (gameMode == null) {
                Logger.error("Network handle null");
                basePath = basePath.resolve("UNKNOWN");
            } else {
                var info = gameMode.connection.getServerData();
                if (info == null) {
                    Logger.error("Server info null");
                    basePath = basePath.resolve("UNKNOWN");
                } else if (info.isRealm()) {
                    basePath = basePath.resolve("realms");
                } else {
                    basePath = basePath.resolve(info.ip.replace(":", "_"));
                }
            }
        }
        return basePath.toAbsolutePath();
    }
}
