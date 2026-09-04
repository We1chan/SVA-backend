package com.ruoyi.waring.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.waring.domain.Gb28181Channel;
import com.ruoyi.waring.domain.Gb28181MediaStream;
import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.waring.domain.ZlmServer;
import com.ruoyi.waring.mapper.Gb28181CatalogMapper;
import com.ruoyi.waring.mapper.HDeviceMapper;
import com.ruoyi.waring.mapper.ZlmServerMapper;
import com.ruoyi.waring.service.impl.Gb28181DeviceSyncServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    public void refreshFromZlmMarksMatchingCatalogChannelAvailable() {
        RecordingCatalogMapper catalog = new RecordingCatalogMapper();
        catalog.seed(channel());
        DeviceMirror devices = new DeviceMirror(null);
        Gb28181DeviceSyncService service = new Gb28181DeviceSyncServiceImpl(
            catalog, mapper(ZlmServerMapper.class, "selectEnabledById", zlmServer()),
            devices.proxy(), returningRestTemplate(
                "{\"code\":0,\"data\":[{\"vhost\":\"__defaultVhost__\",\"app\":\"live\",\"stream\":\"gb-camera-01\"}]}"));

        Gb28181DeviceSyncService.MediaRefreshResult result = service.refreshMediaFromZlm(1L);

        assertEquals(1, result.getAvailable());
        assertEquals(0, result.getUnavailable());
        assertEquals(1, devices.onlineUpdates.size());
        assertEquals("1", devices.onlineUpdates.get(0)[3]);
    }

    @Test
    public void refreshFromZlmWithEmptyMediaListMarksCatalogChannelsOffline() {
        RecordingCatalogMapper catalog = new RecordingCatalogMapper();
        catalog.seed(channel());
        DeviceMirror devices = new DeviceMirror(null);
        Gb28181DeviceSyncService service = new Gb28181DeviceSyncServiceImpl(
            catalog, mapper(ZlmServerMapper.class, "selectEnabledById", zlmServer()),
            devices.proxy(), returningRestTemplate("{\"code\":0,\"data\":[]}"));

        Gb28181DeviceSyncService.MediaRefreshResult result = service.refreshMediaFromZlm(1L);

        assertEquals(0, result.getAvailable());
        assertEquals(1, result.getUnavailable());
        assertEquals(1, devices.offlineUpdates.size());
        assertEquals("2", devices.offlineUpdates.get(0)[3]);
    }

    @Test
    public void refreshFromZlmConnectionFailureThrowsAndDoesNotTouchCatalog() {
        RecordingCatalogMapper catalog = new RecordingCatalogMapper();
        catalog.seed(channel());
        DeviceMirror devices = new DeviceMirror(null);
        Gb28181DeviceSyncService service = new Gb28181DeviceSyncServiceImpl(
            catalog, mapper(ZlmServerMapper.class, "selectEnabledById", zlmServer()),
            devices.proxy(), failingRestTemplate());

        ServiceException error = assertThrows(ServiceException.class,
            () -> service.refreshMediaFromZlm(1L));
        assertEquals("调用 ZLM 媒体列表失败", error.getMessage());
        assertEquals(0, catalog.mediaUnavailableUpdates);
        assertEquals(0, devices.offlineUpdates.size());
    }

    @Test
    public void syncDevicesCreatesMissingMirrorsUpdatesExistingAndOfflinesDroppedChannels() {
        RecordingCatalogMapper catalog = new RecordingCatalogMapper();
        catalog.seed(channel());
        catalog.seed(channelWithChannelId("34020000001310000002"));
        HDevice existed = new HDevice();
        existed.setApe_id("gb-34020000001320000001-34020000001310000001");
        existed.setGb_device_id("34020000001320000001");
        existed.setGb_channel_id("34020000001310000001");
        existed.setMonitor_status("RUNNING");
        DeviceMirror devices = new DeviceMirror(existed);
        Gb28181DeviceSyncService service = new Gb28181DeviceSyncServiceImpl(
            catalog, mapper(ZlmServerMapper.class, "selectEnabledById", zlmServer()),
            devices.proxy(), null);

        Gb28181DeviceSyncService.DeviceSyncResult result =
            service.syncDevices(1L, List.of(channel(), channelWithChannelId("34020000001310000099")));

        assertEquals(1, result.getCreated());
        assertEquals(1, result.getUpdated());
        assertEquals(1, result.getOfflineMarked());
        assertEquals("34020000001310000002", devices.offlineUpdates.get(0)[2]);
        assertEquals("2", devices.offlineUpdates.get(0)[3]);
        assertEquals("STOPPED", devices.inserted.getMonitor_status());
        assertEquals("RUNNING", devices.updated.getMonitor_status());
    }

    @Test
    public void syncDevicesWithoutSnapshotReturnsZeroesAndKeepsState() {
        RecordingCatalogMapper catalog = new RecordingCatalogMapper();
        catalog.seed(channel());
        DeviceMirror devices = new DeviceMirror(null);
        Gb28181DeviceSyncService service = new Gb28181DeviceSyncServiceImpl(
            catalog, mapper(ZlmServerMapper.class, "selectEnabledById", zlmServer()),
            devices.proxy(), null);

        Gb28181DeviceSyncService.DeviceSyncResult result = service.syncDevices(1L, null);

        assertEquals(0, result.getCreated());
        assertEquals(0, result.getUpdated());
        assertEquals(0, result.getOfflineMarked());
        assertEquals(0, catalog.catalogUpserts);
        assertEquals(0, catalog.catalogOfflineUpdates);
        assertEquals(0, devices.offlineUpdates.size());
        assertEquals(0, devices.onlineUpdates.size());
    }

    @Test
    public void syncDevicesWithExplicitEmptySnapshotMarksPreviouslyCatalogedChannelsOffline() {
        RecordingCatalogMapper catalog = new RecordingCatalogMapper();
        catalog.seed(channel());
        DeviceMirror devices = new DeviceMirror(null);
        Gb28181DeviceSyncService service = new Gb28181DeviceSyncServiceImpl(
            catalog, mapper(ZlmServerMapper.class, "selectEnabledById", zlmServer()),
            devices.proxy(), null);

        Gb28181DeviceSyncService.DeviceSyncResult result = service.syncDevices(1L, Collections.emptyList());

        assertEquals(0, result.getCreated());
        assertEquals(0, result.getUpdated());
        assertEquals(1, result.getOfflineMarked());
        assertEquals(1, devices.offlineUpdates.size());
        assertEquals("34020000001310000001", devices.offlineUpdates.get(0)[2]);
        assertEquals("2", devices.offlineUpdates.get(0)[3]);
        assertEquals(0, catalog.catalogUpserts);
        assertEquals(1, catalog.catalogOfflineUpdates);
    }

    @Test
    public void offlineCatalogCannotBePromotedOnlineByAnOldMediaStream() {
        RecordingCatalogMapper catalog = new RecordingCatalogMapper();
        Gb28181Channel channel = channel();
        channel.setCatalogOnline(false);
        catalog.seed(channel);
        DeviceMirror devices = new DeviceMirror(null);
        Gb28181DeviceSyncService service = new Gb28181DeviceSyncServiceImpl(
            catalog, mapper(ZlmServerMapper.class, "selectEnabledById", zlmServer()), devices.proxy(), null);

        service.syncDevices(1L, List.of(channel));
        assertEquals("2", devices.inserted.getIs_online());
        service.refreshMedia(1L, List.of(new Gb28181MediaStream("__defaultVhost__", "live", "gb-camera-01")));

        assertEquals(0, devices.onlineUpdates.size());
        assertEquals(1, devices.offlineUpdates.size());
    }

    @Test
    public void droppedCatalogCannotBeRevivedByTheNextMediaRefresh() {
        RecordingCatalogMapper catalog = new RecordingCatalogMapper();
        catalog.seed(channel());
        DeviceMirror devices = new DeviceMirror(null);
        Gb28181DeviceSyncService service = new Gb28181DeviceSyncServiceImpl(
            catalog, mapper(ZlmServerMapper.class, "selectEnabledById", zlmServer()), devices.proxy(), null);

        service.syncDevices(1L, Collections.emptyList());
        service.refreshMedia(1L, List.of(new Gb28181MediaStream("__defaultVhost__", "live", "gb-camera-01")));

        assertEquals(0, devices.onlineUpdates.size());
        assertEquals(2, devices.offlineUpdates.size());
        assertEquals(1, catalog.catalogOfflineUpdates);
    }

    @Test
    public void legacyCatalogCannotOverwriteAChannelOwnedByWvp() {
        RecordingCatalogMapper catalog = new RecordingCatalogMapper();
        HDevice wvpDevice = new HDevice();
        wvpDevice.setApe_id("GB_WVP");
        wvpDevice.setGb_device_id(channel().getDeviceId());
        wvpDevice.setGb_channel_id(channel().getChannelId());
        wvpDevice.setStream_source_type("GB28181");
        DeviceMirror devices = new DeviceMirror(wvpDevice);
        Gb28181DeviceSyncService service = new Gb28181DeviceSyncServiceImpl(
            catalog, mapper(ZlmServerMapper.class, "selectEnabledById", zlmServer()), devices.proxy(), null);

        assertThrows(ServiceException.class, () -> service.syncDevices(1L, List.of(channel())));

        assertEquals(0, catalog.catalogUpserts);
    }

    private Gb28181Channel channel() {
        return channelWithChannelId("34020000001310000001");
    }

    private Gb28181Channel channelWithChannelId(String channelId) {
        return new Gb28181Channel("34020000002000000001", "34020000001320000001",
            channelId, "主井口", true, 1L,
            "__defaultVhost__", "live", "gb-camera-01", "ws://media.example/live/gb-camera-01.live.flv");
    }

    private ZlmServer zlmServer() {
        ZlmServer server = new ZlmServer();
        server.setId(1L);
        server.setHost("media.example");
        server.setApi_port(9993);
        server.setSecret("secret");
        return server;
    }

    @SuppressWarnings("unchecked")
    private <T> T mapper(Class<T> mapperType, String supportedMethod, Object result) {
        return (T) Proxy.newProxyInstance(
            mapperType.getClassLoader(),
            new Class<?>[] { mapperType },
            (proxy, method, args) -> method.getName().equals(supportedMethod) ? result : null
        );
    }

    private RestTemplate returningRestTemplate(String body) {
        return new RestTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> ResponseEntity<T> getForEntity(String url, Class<T> responseType, Object... uriVariables) {
                return (ResponseEntity<T>) new ResponseEntity<>(body, HttpStatus.OK);
            }
        };
    }

    private RestTemplate failingRestTemplate() {
        return new RestTemplate() {
            @Override
            public <T> ResponseEntity<T> getForEntity(String url, Class<T> responseType, Object... uriVariables) {
                throw new ResourceAccessException("connect fail", new IOException("boom"));
            }
        };
    }

    /** In-memory catalog: upsert stores by GB identity; tracks media availability updates. */
    private static class RecordingCatalogMapper implements Gb28181CatalogMapper {
        private final Map<String, Gb28181Channel> channels = new LinkedHashMap<>();
        private Gb28181Channel cataloged;
        private int catalogUpserts;
        private int mediaAvailableUpdates;
        private int mediaUnavailableUpdates;
        private int catalogOfflineUpdates;

        private void seed(Gb28181Channel channel) {
            channels.put(channel.getDeviceId() + "\u0000" + channel.getChannelId(), channel);
        }

        @Override
        public int upsertCatalogChannel(Gb28181Channel channel) {
            cataloged = channel;
            seed(channel);
            catalogUpserts++;
            return 1;
        }

        @Override
        public List<Gb28181Channel> selectChannelsByZlmServerId(Long zlmServerId) {
            if (channels.isEmpty()) {
                return Collections.emptyList();
            }
            List<Gb28181Channel> list = new ArrayList<>();
            for (Gb28181Channel channel : channels.values()) {
                if (zlmServerId.equals(channel.getZlmServerId())) {
                    list.add(channel);
                }
            }
            return list;
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

        @Override
        public int markCatalogChannelOffline(Long zlmServerId, String deviceId, String channelId) {
            Gb28181Channel channel = channels.get(deviceId + "\u0000" + channelId);
            if (channel == null || !zlmServerId.equals(channel.getZlmServerId())) {
                return 0;
            }
            channel.setCatalogOnline(false);
            catalogOfflineUpdates++;
            return 1;
        }
    }

    /** Proxy-backed h_device mirror store recording per-channel online state updates. */
    private static class DeviceMirror {
        private final Map<String, HDevice> mirrors = new LinkedHashMap<>();
        private final List<String[]> offlineUpdates = new ArrayList<>();
        private final List<String[]> onlineUpdates = new ArrayList<>();
        private HDevice inserted;
        private HDevice updated;

        private DeviceMirror(HDevice existedMirror) {
            if (existedMirror != null) {
                mirrors.put(existedMirror.getGb_device_id() + "\u0000" + existedMirror.getGb_channel_id(), existedMirror);
            }
        }

        @SuppressWarnings("unchecked")
        private HDeviceMapper proxy() {
            return (HDeviceMapper) Proxy.newProxyInstance(
                HDeviceMapper.class.getClassLoader(),
                new Class<?>[] { HDeviceMapper.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "selectGbDevice":
                            String gbDeviceId = (String) args[1];
                            String gbChannelId = (String) args[2];
                            return mirrors.get(gbDeviceId + "\u0000" + gbChannelId);
                        case "upsertGbDevice":
                            HDevice device = (HDevice) args[0];
                            HDevice existed = mirrors.get(device.getGb_device_id() + "\u0000" + device.getGb_channel_id());
                            if (existed == null) {
                                inserted = device;
                            } else {
                                existed.setMonitor_status(device.getMonitor_status());
                                existed.setPlay_url(device.getPlay_url());
                                updated = existed;
                            }
                            mirrors.put(device.getGb_device_id() + "\u0000" + device.getGb_channel_id(), device);
                            return 1;
                        case "updateGbDeviceOnlineByChannel":
                            String online = (String) args[3];
                            String[] record = { String.valueOf(args[0]), (String) args[1], (String) args[2], online };
                            ("2".equals(online) ? offlineUpdates : onlineUpdates).add(record);
                            return 1;
                        default:
                            return method.getReturnType() == int.class ? 0
                                : method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                    }
                });
        }
    }
}
