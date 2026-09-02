package com.ruoyi.waring.service;

import com.ruoyi.waring.domain.Gb28181Channel;
import com.ruoyi.waring.domain.Gb28181MediaStream;
import com.ruoyi.waring.mapper.Gb28181CatalogMapper;
import com.ruoyi.waring.service.impl.Gb28181DeviceSyncServiceImpl;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The SIP/GB catalog owns identity; ZLM only confirms explicit media bindings. */
public class Gb28181DeviceSyncServiceTest {

    @Test
    public void catalogChannelKeepsItsIdentityWhenItsBoundZlmStreamIsAbsent() {
        RecordingCatalogMapper mapper = new RecordingCatalogMapper();
        Gb28181DeviceSyncService service = new Gb28181DeviceSyncServiceImpl(mapper);
        Gb28181Channel channel = channel();

        service.syncCatalog(List.of(channel));
        Gb28181DeviceSyncService.MediaRefreshResult result = service.refreshMedia(
            1L, List.of(new Gb28181MediaStream("__defaultVhost__", "live", "unrelated")));

        assertEquals(1, mapper.catalogUpserts);
        assertEquals("34020000001320000001", mapper.cataloged.getDeviceId());
        assertEquals("34020000001310000001", mapper.cataloged.getChannelId());
        assertTrue(mapper.cataloged.isCatalogOnline());
        assertEquals(1, mapper.mediaUnavailableUpdates);
        assertEquals(0, result.getAvailable());
        assertEquals(1, result.getUnavailable());
    }

    @Test
    public void zlmMediaOnlyUpdatesTheCatalogChannelWithTheSameExplicitBinding() {
        RecordingCatalogMapper mapper = new RecordingCatalogMapper();
        Gb28181DeviceSyncService service = new Gb28181DeviceSyncServiceImpl(mapper);
        service.syncCatalog(List.of(channel()));

        Gb28181DeviceSyncService.MediaRefreshResult result = service.refreshMedia(
            1L, List.of(new Gb28181MediaStream("__defaultVhost__", "live", "gb-camera-01")));

        assertEquals(1, mapper.mediaAvailableUpdates);
        assertEquals(0, mapper.mediaUnavailableUpdates);
        assertEquals(1, result.getAvailable());
        assertEquals(0, result.getUnavailable());
    }

    private Gb28181Channel channel() {
        return new Gb28181Channel("34020000002000000001", "34020000001320000001",
            "34020000001310000001", "主井口", true, 1L,
            "__defaultVhost__", "live", "gb-camera-01", "ws://media.example/live/gb-camera-01.live.flv");
    }

    private static class RecordingCatalogMapper implements Gb28181CatalogMapper {
        private Gb28181Channel cataloged;
        private int catalogUpserts;
        private int mediaAvailableUpdates;
        private int mediaUnavailableUpdates;

        @Override
        public int upsertCatalogChannel(Gb28181Channel channel) {
            cataloged = channel;
            catalogUpserts++;
            return 1;
        }

        @Override
        public List<Gb28181Channel> selectChannelsByZlmServerId(Long zlmServerId) {
            return cataloged == null ? List.of() : List.of(cataloged);
        }

        @Override
        public int updateMediaAvailability(Long id, boolean available) {
            if (available) {
                mediaAvailableUpdates++;
            } else {
                mediaUnavailableUpdates++;
            }
            return 1;
        }
    }
}
