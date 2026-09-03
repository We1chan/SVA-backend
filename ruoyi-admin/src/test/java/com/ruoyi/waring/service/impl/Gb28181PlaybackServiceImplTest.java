package com.ruoyi.waring.service.impl;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.waring.domain.Gb28181PlaybackInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 验证 WVP 点播响应解包、播放地址选择和错误传播契约。 */
class Gb28181PlaybackServiceImplTest {

    private Gb28181PlaybackServiceImpl service;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        service = new Gb28181PlaybackServiceImpl();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "wvpBaseUrl", "http://wvp.local/");
        ReflectionTestUtils.setField(service, "playTimeoutMs", 20000);
        ReflectionTestUtils.invokeMethod(service, "configureHttpClient");
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    void startsAndUnwrapsWvpPlaybackResponse() {
        server.expect(requestTo("http://wvp.local/api/play/start/34020000001320000001/34020000001310000001"))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"成功","data":{"stream":"gb_stream_1",
                        "mediaServerId":"easysva-gb28181","ws_flv":"ws://10.0.0.2:9996/rtp/gb_stream_1.live.flv",
                        "rtsp":"rtsp://10.0.0.2:9997/rtp/gb_stream_1"}}
                        """, MediaType.APPLICATION_JSON));

        Gb28181PlaybackInfo result = service.start(
                "34020000001320000001", "34020000001310000001");

        assertEquals("gb_stream_1", result.getStreamId());
        assertEquals("easysva-gb28181", result.getMediaServerId());
        assertEquals("ws://10.0.0.2:9996/rtp/gb_stream_1.live.flv", result.getPlayUrl());
        assertEquals("rtsp://10.0.0.2:9997/rtp/gb_stream_1", result.getRtspUrl());
        server.verify();
    }

    @Test
    void rejectsIncompletePlaybackResponse() {
        server.expect(requestTo("http://wvp.local/api/play/start/device/channel"))
                .andRespond(withSuccess("{" +
                        "\"code\":0,\"data\":{\"stream\":\"gb_stream_1\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://wvp.local/api/play/stop/device/channel"))
                .andRespond(withSuccess("{\"code\":0,\"data\":null}", MediaType.APPLICATION_JSON));

        assertThrows(ServiceException.class, () -> service.start("device", "channel"));
        server.verify();
    }

    @Test
    void rejectsUnrecognizedStopResponse() {
        server.expect(requestTo("http://wvp.local/api/play/stop/device/channel"))
                .andRespond(withSuccess("{\"error\":\"login required\"}", MediaType.APPLICATION_JSON));

        assertThrows(ServiceException.class, () -> service.stop("device", "channel"));

        server.verify();
    }
}
