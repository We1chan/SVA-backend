package com.ruoyi.waring.service.impl;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.waring.domain.Gb28181PtzCommand;
import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.waring.mapper.HDeviceMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class Gb28181PtzServiceImplTest {

    private Gb28181PtzServiceImpl service;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        service = new Gb28181PtzServiceImpl();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "wvpBaseUrl", "http://wvp.local/");
        ReflectionTestUtils.setField(service, "requestTimeoutMs", 3000);
        ReflectionTestUtils.setField(service, "safetyStopMs", 60000L);
        ReflectionTestUtils.setField(service, "hDeviceMapper", mapper(device("1", "GB28181")));
        ReflectionTestUtils.invokeMethod(service, "configureHttpClient");
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.invokeMethod(service, "shutdown");
    }

    @Test
    void dispatchesAllowlistedCommandWithScaledSpeed() {
        server.expect(requestTo("http://wvp.local/api/front-end/ptz/34020000001320000001/34020000001310000001?command=left&horizonSpeed=128&verticalSpeed=64&zoomSpeed=15"))
                .andRespond(withSuccess("", MediaType.TEXT_PLAIN));
        server.expect(requestTo("http://wvp.local/api/front-end/ptz/34020000001320000001/34020000001310000001?command=stop&horizonSpeed=0&verticalSpeed=0&zoomSpeed=0"))
                .andRespond(withSuccess("", MediaType.TEXT_PLAIN));

        Gb28181PtzCommand command = command("left", 50, 25, 100);
        Map<String, Object> result = service.control("gb-1", command);

        assertEquals(Boolean.TRUE, result.get("accepted"));
        assertEquals("left", result.get("command"));
        service.control("gb-1", command("stop", 50, 50, 50));
        server.verify();
    }

    @Test
    void rejectsOfflineDeviceBeforeCallingWvp() {
        ReflectionTestUtils.setField(service, "hDeviceMapper", mapper(device("0", "GB28181")));
        assertThrows(ServiceException.class, () -> service.control("gb-1", command("left", 50, 50, 50)));
        server.verify();
    }

    @Test
    void rejectsRtspDeviceAndUnknownCommand() {
        ReflectionTestUtils.setField(service, "hDeviceMapper", mapper(device("1", "RTSP")));
        assertThrows(ServiceException.class, () -> service.control("rtsp-1", command("left", 50, 50, 50)));

        ReflectionTestUtils.setField(service, "hDeviceMapper", mapper(device("1", "GB28181")));
        assertThrows(ServiceException.class, () -> service.control("gb-1", command("reboot", 50, 50, 50)));
        server.verify();
    }

    private Gb28181PtzCommand command(String value, int pan, int tilt, int zoom) {
        Gb28181PtzCommand command = new Gb28181PtzCommand();
        command.setCommand(value);
        command.setPanSpeed(pan);
        command.setTiltSpeed(tilt);
        command.setZoomSpeed(zoom);
        return command;
    }

    private HDevice device(String online, String type) {
        HDevice device = new HDevice();
        device.setApe_id("gb-1");
        device.setDevice_type(type);
        device.setStream_source_type(type);
        device.setIs_online(online);
        device.setGb_device_id("34020000001320000001");
        device.setGb_channel_id("34020000001310000001");
        return device;
    }

    private HDeviceMapper mapper(HDevice device) {
        return (HDeviceMapper) Proxy.newProxyInstance(
                HDeviceMapper.class.getClassLoader(), new Class<?>[] { HDeviceMapper.class },
                (proxy, method, args) -> "selectDeviceByApeId".equals(method.getName()) ? device
                        : method.getReturnType() == int.class ? 0 : null);
    }
}
