package com.ruoyi.waring.service;

import com.ruoyi.waring.domain.Gb28181Channel;
import com.ruoyi.waring.domain.Gb28181MediaStream;

import java.util.List;

public interface Gb28181DeviceSyncService {
    CatalogSyncResult syncCatalog(List<Gb28181Channel> channels);

    MediaRefreshResult refreshMedia(Long zlmServerId, List<Gb28181MediaStream> streams);

    MediaRefreshResult refreshMediaFromZlm(Long zlmServerId);

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
}
