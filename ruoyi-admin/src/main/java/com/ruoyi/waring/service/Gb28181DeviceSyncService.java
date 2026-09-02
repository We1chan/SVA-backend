package com.ruoyi.waring.service;

import com.ruoyi.waring.domain.Gb28181Channel;
import com.ruoyi.waring.domain.Gb28181MediaStream;

import java.util.List;

public interface Gb28181DeviceSyncService {
    CatalogSyncResult syncCatalog(List<Gb28181Channel> channels);

    MediaRefreshResult refreshMedia(Long zlmServerId, List<Gb28181MediaStream> streams);

    MediaRefreshResult refreshMediaFromZlm(Long zlmServerId);

    /**
     * Reconcile a SIP/GB catalog snapshot onto one ZLM node. A {@code null}
     * {@code channels} value means no snapshot was supplied and is a no-op;
     * an explicit empty list is an authoritative empty snapshot and marks all
     * previously catalogued channels for the node offline.
     *
     * <p>A non-null snapshot upserts its catalog rows, mirrors GB28181 devices
     * into {@code h_device} idempotently, and marks channels absent from the
     * snapshot offline.</p>
     */
    DeviceSyncResult syncDevices(Long zlmServerId, List<Gb28181Channel> channels);

    class CatalogSyncResult {
        private int synchronizedChannels;

        public int getSynchronizedChannels() { return synchronizedChannels; }
        public void incrementSynchronizedChannels() { synchronizedChannels++; }
    }

    class MediaRefreshResult {
        private int available;
        private int unavailable;

        public int getAvailable() { return available; }
        public void incrementAvailable() { available++; }
        public int getUnavailable() { return unavailable; }
        public void incrementUnavailable() { unavailable++; }
    }

    class DeviceSyncResult {
        private int created;
        private int updated;
        private int offlineMarked;

        public int getCreated() { return created; }
        public int getUpdated() { return updated; }
        public int getOfflineMarked() { return offlineMarked; }
        public void incrementCreated() { created++; }
        public void incrementUpdated() { updated++; }
        public void addOfflineMarked(int rows) { offlineMarked += rows; }
    }
}
