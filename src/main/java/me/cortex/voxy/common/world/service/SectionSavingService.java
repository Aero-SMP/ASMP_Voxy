package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.concurrent.ConcurrentLinkedDeque;

//TODO: add an option for having synced saving, that is when call enqueueSave, that will instead, instantly
// save to the db, this can be useful for just reducing the amount of thread pools in total
// might have some issues with threading if the same section is saved from multiple threads?
public class SectionSavingService {
    private static final int SOFT_MAX_QUEUE_SIZE = 5_000;

    private final Service service;
    private record SaveEntry(WorldEngine engine, WorldSection section, long revision) {}
    private final ConcurrentLinkedDeque<SaveEntry> saveQueue = new ConcurrentLinkedDeque<>();

    public SectionSavingService(ServiceManager sm) {
        this.service = sm.createServiceNoCleanup(() -> this::processJob, 100, "Section saving service");
    }

    private void processJob() {
        var task = this.saveQueue.pop();
        var section = task.section;
        boolean saveAttempted = false;
        try {
            synchronized (section) {
                if (section.exchangeIsInSaveQueue(false)
                        && section.getStorageRevision() == task.revision) {
                    section.setNotDirty();
                    saveAttempted = true;
                    task.engine.storage.saveSection(section);
                }
            }
        } catch (Exception e) {
            if (saveAttempted) {
                synchronized (section) {
                    // Retry only the snapshot that failed. A newer invalidation may already
                    // have durably deleted the key and must never be resurrected by this task.
                    if (section.getStorageRevision() == task.revision && task.engine.isLive()) {
                        section.markDirty();
                    }
                }
            }
            Logger.error("Voxy saver had an exception while executing please check logs and report error", e);
        }
        section.release();
    }

    public boolean enqueueSave(WorldEngine in, WorldSection section, boolean nonBlocking, boolean sectionAlreadyAcquired) {
        //If its not enqueued for saving then enqueue it
        long revision;
        synchronized (section) {
            if (!section.exchangeIsInSaveQueue(true)) return false;
            revision = section.getStorageRevision();
            if (!sectionAlreadyAcquired) {
                section.acquire(); //Acquire the section for use
            }
        }

        //Hard limit the save count to prevent OOM
        if ((!nonBlocking) && this.getTaskCount() > SOFT_MAX_QUEUE_SIZE) {
            //wait a bit
            Thread.yield();
            //If we are still full, process entries in the queue ourselves instead of waiting for the service
            while (this.getTaskCount() > SOFT_MAX_QUEUE_SIZE && this.service.isLive()) {
                if (!this.service.steal()) {
                    break;
                }
                this.processJob();
            }
        }

        this.saveQueue.add(new SaveEntry(in, section, revision));
        this.service.execute();
        return true;
    }

    public void shutdown() {
        if (this.service.numJobs() != 0) {
            Logger.error("Voxy section saving still in progress, estimated " + this.service.numJobs() + " sections remaining.");
            this.service.blockTillEmpty();
        }
        this.service.shutdown();
        //Manually save any remaining entries
        while (!this.saveQueue.isEmpty()) {
            this.processJob();
        }
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }
}
