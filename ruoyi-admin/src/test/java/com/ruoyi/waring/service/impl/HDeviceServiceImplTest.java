package com.ruoyi.waring.service.impl;

import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.waring.domain.ZlmServer;
import com.ruoyi.waring.mapper.HDeviceMapper;
import com.ruoyi.waring.mapper.ZlmServerMapper;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class HDeviceServiceImplTest {

    @Test
    public void previewMonitorUsesConfiguredZlmWsFlvForDirectRtspSource() {
        HDevice device = new HDevice();
        device.setApe_id("camera-01");
        device.setName("Camera 01");
        device.setStream_source_type("DIRECT");
        device.setPlay_url("rtsp://camera.example/live");
        device.setZlm_server_id(1L);

        ZlmServer zlmServer = new ZlmServer();
        zlmServer.setHost("zlm.example");
        zlmServer.setMedia_http_port(8080);
        zlmServer.setApp("live");

        HDeviceServiceImpl service = new HDeviceServiceImpl();
        service.hDeviceMapper = mapper(HDeviceMapper.class, "selectDeviceByApeId", device);
        service.zlmServerMapper = mapper(ZlmServerMapper.class, "selectEnabledById", zlmServer);

        Map<String, Object> preview = service.previewMonitor("camera-01");

        assertEquals("ws://zlm.example:8080/live/camera-01.live.flv", preview.get("playUrl"));
    }

    @SuppressWarnings("unchecked")
    private <T> T mapper(Class<T> mapperType, String supportedMethod, Object result) {
        return (T) Proxy.newProxyInstance(
            mapperType.getClassLoader(),
            new Class<?>[] { mapperType },
            (proxy, method, args) -> method.getName().equals(supportedMethod) ? result : null
        );
    }
}
