package com.ruoyi.waring.service.impl;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.waring.domain.Gb28181PlaybackInfo;
import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.waring.domain.ZlmServer;
import com.ruoyi.waring.mapper.HDeviceMapper;
import com.ruoyi.waring.mapper.ZlmServerMapper;
import com.ruoyi.waring.service.Gb28181PlaybackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证两条监控路线的生命周期隔离：
 * <ul>
 *   <li>WVP 点播路线（stream_source_type='GB28181'）：INVITE 点播启动/停止/预览；</li>
 *   <li>目录同步路线与 DIRECT：预览统一转换为 ZLM ws/flv 拉流地址。</li>
 * </ul>
 */
class HDeviceServiceImplTest {

    private HDeviceServiceImpl service;
    private HDeviceMapper mapper;
    private ZlmServerMapper zlmServerMapper;
    private Gb28181PlaybackService playbackService;

    @BeforeEach
    void setUp() {
        service = new HDeviceServiceImpl();
        mapper = mock(HDeviceMapper.class);
        zlmServerMapper = mock(ZlmServerMapper.class);
        playbackService = mock(Gb28181PlaybackService.class);
        ReflectionTestUtils.setField(service, "hDeviceMapper", mapper);
        ReflectionTestUtils.setField(service, "zlmServerMapper", zlmServerMapper);
        ReflectionTestUtils.setField(service, "gb28181PlaybackService", playbackService);
    }

    @Test
    void startsGb28181PlaybackAndPersistsBothPreviewAndAnalyzerUrls() {
        HDevice device = gbDevice("1");
        when(mapper.selectDeviceByApeId("GB_TEST")).thenReturn(device);
        Gb28181PlaybackInfo playback = new Gb28181PlaybackInfo();
        playback.setStreamId("stream-1");
        playback.setMediaServerId("easysva-gb28181");
        playback.setPlayUrl("ws://10.0.0.2:9996/rtp/stream-1.live.flv");
        playback.setRtspUrl("rtsp://10.0.0.2:9997/rtp/stream-1");
        when(playbackService.start(device.getGb_device_id(), device.getGb_channel_id())).thenReturn(playback);
        when(mapper.updateGb28181Playback(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        when(mapper.updateMonitorStateByApeId("GB_TEST", "RUNNING")).thenReturn(1);

        assertEquals(1, service.startMonitor("GB_TEST"));

        verify(mapper).updateGb28181Playback("GB_TEST", playback.getPlayUrl(), "stream-1",
                playback.getRtspUrl(), "easysva-gb28181");
        verify(mapper).updateMonitorStateByApeId("GB_TEST", "RUNNING");
    }

    @Test
    void rejectsOfflineGb28181StartAndPreview() {
        HDevice device = gbDevice("0");
        when(mapper.selectDeviceByApeId("GB_TEST")).thenReturn(device);

        assertThrows(ServiceException.class, () -> service.startMonitor("GB_TEST"));
        assertThrows(ServiceException.class, () -> service.previewMonitor("GB_TEST"));
        verify(playbackService, never()).start(anyString(), anyString());
    }

    @Test
    void returnsActiveGb28181PreviewUrl() {
        HDevice device = gbDevice("1");
        device.setPlay_url("ws://127.0.0.1:9996/rtp/stream-1.live.flv?token=1");
        device.setGb_stream_url("rtsp://10.0.0.2:9997/rtp/stream-1");
        when(mapper.selectDeviceByApeId("GB_TEST")).thenReturn(device);

        Map<String, Object> preview = service.previewMonitor("GB_TEST");

        assertEquals("/gb-media/rtp/stream-1.live.flv?token=1", preview.get("playUrl"));
        assertEquals(device.getGb_stream_url(), preview.get("streamUrl"));
    }

    @Test
    void preservesRemoteGb28181PreviewUrl() {
        HDevice device = gbDevice("1");
        device.setPlay_url("ws://media.example:9996/rtp/stream-1.live.flv");
        when(mapper.selectDeviceByApeId("GB_TEST")).thenReturn(device);

        Map<String, Object> preview = service.previewMonitor("GB_TEST");

        assertEquals(device.getPlay_url(), preview.get("playUrl"));
    }

    @Test
    void cleansGb28181SessionWhenMonitorStatePersistenceThrows() {
        HDevice device = gbDevice("1");
        when(mapper.selectDeviceByApeId("GB_TEST")).thenReturn(device);
        Gb28181PlaybackInfo playback = new Gb28181PlaybackInfo();
        playback.setStreamId("stream-1");
        playback.setPlayUrl("ws://10.0.0.2:9996/rtp/stream-1.live.flv");
        playback.setRtspUrl("rtsp://10.0.0.2:9997/rtp/stream-1");
        when(playbackService.start(device.getGb_device_id(), device.getGb_channel_id())).thenReturn(playback);
        when(mapper.updateGb28181Playback(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        when(mapper.updateMonitorStateByApeId("GB_TEST", "RUNNING"))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> service.startMonitor("GB_TEST"));

        verify(playbackService).stop(device.getGb_device_id(), device.getGb_channel_id());
        verify(mapper).clearGb28181Playback("GB_TEST");
    }

    @Test
    void directDevicePreviewConvertsSourceRtspToZlmWsFlv() {
        HDevice device = new HDevice();
        device.setApe_id("camera-01");
        device.setName("Camera 01");
        device.setStream_source_type("DIRECT");
        device.setPlay_url("rtsp://camera.example/live");
        device.setZlm_server_id(1L);
        when(mapper.selectDeviceByApeId("camera-01")).thenReturn(device);

        ZlmServer zlmServer = new ZlmServer();
        zlmServer.setHost("zlm.example");
        zlmServer.setMedia_http_port(8080);
        zlmServer.setApp("live");
        when(zlmServerMapper.selectEnabledById(1L)).thenReturn(zlmServer);

        Map<String, Object> preview = service.previewMonitor("camera-01");

        assertEquals("ws://zlm.example:8080/live/camera-01.live.flv", preview.get("playUrl"));
    }

    private HDevice gbDevice(String online) {
        HDevice device = new HDevice();
        device.setApe_id("GB_TEST");
        device.setName("Camera-1");
        device.setStream_source_type("GB28181");
        device.setGb_device_id("34020000001320000001");
        device.setGb_channel_id("34020000001310000001");
        device.setGb_media_server_id("easysva-gb28181");
        device.setIs_online(online);
        device.setMonitor_status("STOPPED");
        return device;
    }
}
