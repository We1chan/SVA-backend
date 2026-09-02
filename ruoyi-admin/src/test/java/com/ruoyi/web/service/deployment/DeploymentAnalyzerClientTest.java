package com.ruoyi.web.service.deployment;

import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.waring.domain.SvaServer;
import com.ruoyi.waring.domain.ZlmServer;
import com.ruoyi.waring.mapper.HDeviceMapper;
import com.ruoyi.waring.mapper.SvaServerMapper;
import com.ruoyi.waring.mapper.ZlmServerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证分析器只在国标通道在线且已点播时消费 WVP/ZLM RTSP 地址。 */
class DeploymentAnalyzerClientTest {

    private DeploymentAnalyzerClient client;
    private HDeviceMapper deviceMapper;

    @BeforeEach
    void setUp() {
        client = new DeploymentAnalyzerClient();
        deviceMapper = mock(HDeviceMapper.class);
        ZlmServerMapper zlmServerMapper = mock(ZlmServerMapper.class);
        SvaServerMapper svaServerMapper = mock(SvaServerMapper.class);
        ReflectionTestUtils.setField(client, "hDeviceMapper", deviceMapper);
        ReflectionTestUtils.setField(client, "zlmServerMapper", zlmServerMapper);
        ReflectionTestUtils.setField(client, "svaServerMapper", svaServerMapper);

        ZlmServer zlm = new ZlmServer();
        zlm.setHost("127.0.0.1");
        zlm.setApp("live");
        zlm.setMedia_rtsp_port(9994);
        zlm.setMedia_rtmp_port(9995);
        zlm.setMedia_http_port(9992);
        when(zlmServerMapper.selectEnabledById(1L)).thenReturn(zlm);

        SvaServer sva = new SvaServer();
        sva.setHost("127.0.0.1");
        sva.setApp("analyzer");
        sva.setAnalyzer_port(19993);
        when(svaServerMapper.selectEnabledById(1L)).thenReturn(sva);
    }

    @Test
    void keepsOriginalRtspRouteForDirectDevices() {
        HDevice direct = new HDevice();
        direct.setApe_id("cam000001");
        direct.setStream_source_type("DIRECT");
        when(deviceMapper.selectDeviceByApeId("cam000001")).thenReturn(direct);

        assertEquals("rtsp://127.0.0.1:9994/live/cam000001", client.buildStreamUrl("cam000001"));
    }

    @Test
    void usesActiveWvpRtspRouteOnlyWhileGbDeviceIsOnline() {
        HDevice gb = new HDevice();
        gb.setApe_id("GB_TEST");
        gb.setStream_source_type("GB28181");
        gb.setIs_online("1");
        gb.setGb_stream_url("rtsp://10.0.0.2:9997/rtp/stream-1");
        when(deviceMapper.selectDeviceByApeId("GB_TEST")).thenReturn(gb);

        assertEquals(gb.getGb_stream_url(), client.buildStreamUrl("GB_TEST"));

        gb.setIs_online("0");
        assertNull(client.buildStreamUrl("GB_TEST"));
    }
}
