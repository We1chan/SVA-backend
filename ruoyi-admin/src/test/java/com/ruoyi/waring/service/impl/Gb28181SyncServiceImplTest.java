package com.ruoyi.waring.service.impl;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.waring.mapper.HDeviceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 验证 WVP 设备/通道快照到 h_device 的同步与在线状态契约。 */
class Gb28181SyncServiceImplTest {

    private Gb28181SyncServiceImpl service;
    private HDeviceMapper mapper;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        service = new Gb28181SyncServiceImpl();
        mapper = mock(HDeviceMapper.class);
        ReflectionTestUtils.setField(service, "hDeviceMapper", mapper);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "wvpBaseUrl", "http://wvp.local");
        ReflectionTestUtils.setField(service, "defaultMediaServerId", "easysva-gb28181");
        ReflectionTestUtils.setField(service, "defaultOrgIndex", "103");
        ReflectionTestUtils.setField(service, "defaultOrgName", "研发部门");
        ReflectionTestUtils.invokeMethod(service, "configureHttpClient");
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    void synchronizesOnlineChannelAndClearsOnlyOfflinePlayback() {
        server.expect(requestTo("http://wvp.local/api/device/query/devices?page=1&count=200"))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"成功","data":{"list":[{"deviceId":"34020000001320000001",
                        "name":"IPC-1","onLine":true,"mediaServerId":"easysva-gb28181"}],"pages":1}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://wvp.local/api/device/query/devices/34020000001320000001/channels?page=1&count=200"))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"成功","data":{"list":[{"deviceId":"34020000001310000001",
                        "name":"Camera-1","status":"ON","channelType":0,"manufacturer":"Test"}],"pages":1}}
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> result = service.syncDevices();

        assertEquals(1, result.get("deviceCount"));
        assertEquals(1, result.get("channelCount"));
        assertEquals(1, result.get("onlineChannelCount"));
        verify(mapper).markAllGb28181DevicesOffline();
        verify(mapper).clearOfflineGb28181Playback();

        ArgumentCaptor<HDevice> captor = ArgumentCaptor.forClass(HDevice.class);
        verify(mapper).upsertGb28181Device(captor.capture());
        HDevice device = captor.getValue();
        assertEquals("GB28181", device.getDevice_type());
        assertEquals("GB28181", device.getStream_source_type());
        assertEquals("34020000001320000001", device.getGb_device_id());
        assertEquals("34020000001310000001", device.getGb_channel_id());
        assertEquals("1", device.getIs_online());
        server.verify();
    }

    @Test
    void rejectsMissingDirectoryInsteadOfOffliningEveryDevice() {
        server.expect(requestTo("http://wvp.local/api/device/query/devices?page=1&count=200"))
                .andRespond(withSuccess("{\"code\":0,\"data\":null}", MediaType.APPLICATION_JSON));

        assertThrows(ServiceException.class, service::syncDevices);

        verifyNoInteractions(mapper);
        server.verify();
    }

    @Test
    void rejectsIncompleteChannelPageBeforeWritingSnapshot() {
        server.expect(requestTo("http://wvp.local/api/device/query/devices?page=1&count=200"))
                .andRespond(withSuccess("{\"list\":[{\"deviceId\":\"device\",\"onLine\":true}],\"pages\":1}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://wvp.local/api/device/query/devices/device/channels?page=1&count=200"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"pages\":2}}", MediaType.APPLICATION_JSON));

        assertThrows(ServiceException.class, service::syncDevices);

        verifyNoInteractions(mapper);
        server.verify();
    }

    @Test
    void acceptsAuthoritativeEmptyDirectory() {
        server.expect(requestTo("http://wvp.local/api/device/query/devices?page=1&count=200"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"list\":[],\"pages\":0,\"total\":0}}",
                        MediaType.APPLICATION_JSON));

        assertEquals(0, service.syncDevices().get("channelCount"));

        verify(mapper).markAllGb28181DevicesOffline();
        verify(mapper).clearOfflineGb28181Playback();
        server.verify();
    }
}
