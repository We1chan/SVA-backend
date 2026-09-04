package com.ruoyi.waring.service;

import com.ruoyi.waring.domain.DeviceMonitorResult;
import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.system.domain.DeploymentTask;
import com.ruoyi.system.service.IDeploymentTaskService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 设备监控启停统一编排服务的业务契约测试。 */
public class DeviceMonitorServiceTest {

    @Test
    public void startReturnsSuccessWhenRowsPositive() {
        HDevice device = device("ape-1");
        DeviceMonitorService service = service((proxy, method, args) -> {
            switch (method.getName()) {
                case "selectDeviceByApeId":
                    return device;
                case "startMonitor":
                    return 1;
                default:
                    return 0;
            }
        });

        DeviceMonitorResult result = service.start("ape-1");

        assertTrue(result.isSuccess());
        assertNull(result.getShortMessage());
        assertSame(device, result.getData());
    }

    @Test
    public void stopReturnsSuccessWhenRowsPositive() {
        HDevice device = device("ape-1");
        DeviceMonitorService service = service((proxy, method, args) -> {
            switch (method.getName()) {
                case "selectDeviceByApeId":
                    return device;
                case "stopMonitor":
                    return 1;
                default:
                    return 0;
            }
        });

        DeviceMonitorResult result = service.stop("ape-1");

        assertTrue(result.isSuccess());
        assertSame(device, result.getData());
    }

    @Test
    public void stopRejectsSourceShutdownWhileDeploymentIsRunning() {
        HDeviceService devices = mock(HDeviceService.class);
        IDeploymentTaskService deployments = mock(IDeploymentTaskService.class);
        HDevice device = device("ape-1");
        DeploymentTask task = new DeploymentTask();
        task.setDeviceId("ape-1");
        when(devices.selectDeviceByApeId("ape-1")).thenReturn(device);
        when(deployments.selectDeploymentTaskList("RUNNING", null, null))
            .thenReturn(Collections.singletonList(task));

        DeviceMonitorResult result = new DeviceMonitorService(devices, deployments).stop("ape-1");

        assertFalse(result.isSuccess());
        assertTrue(result.getShortMessage().contains("先在布控管理中停止布控"));
        verify(devices, never()).stopMonitor("ape-1");
    }

    @Test
    public void returnsNotFoundWhenDeviceMissing() {
        DeviceMonitorService service = service((proxy, method, args) -> {
            if ("selectDeviceByApeId".equals(method.getName())) {
                return null;
            }
            return 0;
        });

        DeviceMonitorResult result = service.start("missing");

        assertFalse(result.isSuccess());
        assertEquals("设备不存在", result.getShortMessage());
        assertNull(result.getData());
    }

    @Test
    public void returnsFailWhenZeroRows() {
        HDevice device = device("ape-1");
        DeviceMonitorService service = service((proxy, method, args) -> {
            switch (method.getName()) {
                case "selectDeviceByApeId":
                    return device;
                case "startMonitor":
                    return 0;
                default:
                    return 0;
            }
        });

        DeviceMonitorResult result = service.start("ape-1");

        assertFalse(result.isSuccess());
        assertEquals("启动监控失败", result.getShortMessage());
        assertSame(device, result.getData());
    }

    @Test
    public void returnsOriginalMessageOnUnknownBusinessException() {
        HDevice device = device("ape-1");
        DeviceMonitorService service = service((proxy, method, args) -> {
            if ("selectDeviceByApeId".equals(method.getName())) {
                return device;
            }
            if ("startMonitor".equals(method.getName())) {
                throw new RuntimeException("业务错误");
            }
            return 0;
        });

        DeviceMonitorResult result = service.start("ape-1");

        assertFalse(result.isSuccess());
        assertEquals("业务错误", result.getShortMessage());
        assertSame(device, result.getData());
    }

    @Test
    public void mapsTimeoutMessage() {
        HDevice device = device("ape-1");
        DeviceMonitorService service = service((proxy, method, args) -> {
            if ("selectDeviceByApeId".equals(method.getName())) {
                return device;
            }
            if ("startMonitor".equals(method.getName())) {
                throw new RuntimeException("connection timeout while starting");
            }
            return 0;
        });

        DeviceMonitorResult result = service.start("ape-1");

        assertFalse(result.isSuccess());
        assertEquals("连接超时", result.getShortMessage());
    }

    @Test
    public void mapsPullStreamErrorMessage() {
        HDevice device = device("ape-1");
        DeviceMonitorService service = service((proxy, method, args) -> {
            if ("selectDeviceByApeId".equals(method.getName())) {
                return device;
            }
            if ("startMonitor".equals(method.getName())) {
                throw new RuntimeException("pull stream connect error");
            }
            return 0;
        });

        DeviceMonitorResult result = service.start("ape-1");

        assertFalse(result.isSuccess());
        assertEquals("读取视频流失败，请确认设备启动了视频流", result.getShortMessage());
    }

    @Test
    public void mapsPushStreamErrorMessage() {
        HDevice device = device("ape-1");
        DeviceMonitorService service = service((proxy, method, args) -> {
            if ("selectDeviceByApeId".equals(method.getName())) {
                return device;
            }
            if ("startMonitor".equals(method.getName())) {
                throw new RuntimeException("push stream connect error");
            }
            return 0;
        });

        DeviceMonitorResult result = service.start("ape-1");

        assertFalse(result.isSuccess());
        assertEquals("推送失败，请稍后再试！", result.getShortMessage());
    }

    @Test
    public void mapsAlreadyExistsMessage() {
        HDevice device = device("ape-1");
        DeviceMonitorService service = service((proxy, method, args) -> {
            if ("selectDeviceByApeId".equals(method.getName())) {
                return device;
            }
            if ("startMonitor".equals(method.getName())) {
                throw new RuntimeException("monitor already exists");
            }
            return 0;
        });

        DeviceMonitorResult result = service.start("ape-1");

        assertFalse(result.isSuccess());
        assertEquals("设备监控已经启动过", result.getShortMessage());
    }

    @Test
    public void keepsSnapshotWhenRereadFails() {
        HDevice snapshot = device("ape-1");
        // 第一次 selectDeviceByApeId 返回快照；startMonitor 抛业务异常；
        // 失败后的重读（第二次 selectDeviceByApeId）再抛异常。
        DeviceMonitorService service = service(new InvocationHandler() {
            private boolean firstSelect = true;

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                switch (method.getName()) {
                    case "selectDeviceByApeId":
                        if (firstSelect) {
                            firstSelect = false;
                            return snapshot;
                        }
                        throw new IllegalStateException("重读失败");
                    case "startMonitor":
                        throw new RuntimeException("业务错误");
                    default:
                        return 0;
                }
            }
        });

        DeviceMonitorResult result = service.start("ape-1");

        assertFalse(result.isSuccess());
        assertEquals("业务错误", result.getShortMessage());
        assertNotNull(result.getData());
        assertSame(snapshot, result.getData());
    }

    private HDevice device(String apeId) {
        HDevice device = new HDevice();
        device.setApe_id(apeId);
        return device;
    }

    private DeviceMonitorService service(InvocationHandler handler) {
        HDeviceService mock = (HDeviceService) Proxy.newProxyInstance(
            HDeviceService.class.getClassLoader(),
            new Class<?>[] { HDeviceService.class },
            handler);
        return new DeviceMonitorService(mock);
    }
}
