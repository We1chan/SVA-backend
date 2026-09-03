package com.ruoyi.waring.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.waring.domain.DeviceMonitorResult;
import com.ruoyi.waring.domain.Gb28181Channel;
import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.waring.service.DeviceMonitorService;
import com.ruoyi.waring.service.HDeviceService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HTTP-facing contract of the GB28181 catalog sync and status refresh endpoints. */
public class HDeviceControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    public void catalogSyncReturnsCreatedUpdatedOfflineMarked() {
        HDeviceController controller = new HDeviceController();
        controller.hDeviceService = deviceService(syncPayload(), refreshPayload());

        AjaxResult result = controller.gb28181CatalogSync(1L, List.of());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<String, Object> payload = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertEquals(1, payload.get("created"));
        assertEquals(2, payload.get("updated"));
        assertEquals(0, payload.get("offlineMarked"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void statusRefreshReturnsAvailableUnavailable() {
        HDeviceController controller = new HDeviceController();
        controller.hDeviceService = deviceService(syncPayload(), refreshPayload());

        AjaxResult result = controller.gb28181StatusRefresh(1L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<String, Object> payload = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertEquals(3, payload.get("available"));
        assertEquals(1, payload.get("unavailable"));
    }

    @Test
    public void catalogSyncPropagatesErrorWhenZlmUnreachable() {
        HDeviceController controller = new HDeviceController();
        controller.hDeviceService = failingDeviceService();

        assertThrows(ServiceException.class, () -> controller.gb28181CatalogSync(1L, List.of()));
    }

    private Map<String, Object> syncPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("created", 1);
        payload.put("updated", 2);
        payload.put("offlineMarked", 0);
        return payload;
    }

    private Map<String, Object> refreshPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("available", 3);
        payload.put("unavailable", 1);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private HDeviceService deviceService(Map<String, Object> sync, Map<String, Object> refresh) {
        return (HDeviceService) Proxy.newProxyInstance(
            HDeviceService.class.getClassLoader(),
            new Class<?>[] { HDeviceService.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "syncGb28181Catalog":
                        return sync;
                    case "refreshGb28181Status":
                        return refresh;
                    default:
                        return method.getReturnType() == int.class ? 0
                            : method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                }
            });
    }

    @SuppressWarnings("unchecked")
    private HDeviceService failingDeviceService() {
        return (HDeviceService) Proxy.newProxyInstance(
            HDeviceService.class.getClassLoader(),
            new Class<?>[] { HDeviceService.class },
            (proxy, method, args) -> {
                if ("syncGb28181Catalog".equals(method.getName())) {
                    throw new ServiceException("调用 ZLM 媒体列表失败");
                }
                return null;
            });
    }

    // ===== 监控启停端点契约测试：验证 Controller 只做包装 =====

    @Test
    public void startMonitorWrapsServiceSuccess() {
        HDeviceController controller = new HDeviceController();
        controller.deviceMonitorService = new DeviceMonitorService(monitorService(1, device("ape-1")));

        AjaxResult result = controller.startMonitor("ape-1");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        DeviceMonitorResult payload = (DeviceMonitorResult) result.get(AjaxResult.DATA_TAG);
        assertTrue(payload.isSuccess());
        assertNotNull(payload.getData());
    }

    @Test
    public void startMonitorWrapsDeviceNotFound() {
        HDeviceController controller = new HDeviceController();
        controller.deviceMonitorService = new DeviceMonitorService(monitorService(0, null));

        AjaxResult result = controller.startMonitor("missing");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        DeviceMonitorResult payload = (DeviceMonitorResult) result.get(AjaxResult.DATA_TAG);
        assertFalse(payload.isSuccess());
        assertEquals("设备不存在", payload.getShortMessage());
        assertNull(payload.getData());
    }

    private HDevice device(String apeId) {
        HDevice device = new HDevice();
        device.setApe_id(apeId);
        return device;
    }

    @SuppressWarnings("unchecked")
    private HDeviceService monitorService(int rows, HDevice device) {
        return (HDeviceService) Proxy.newProxyInstance(
            HDeviceService.class.getClassLoader(),
            new Class<?>[] { HDeviceService.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "selectDeviceByApeId":
                        return device;
                    case "startMonitor":
                    case "stopMonitor":
                        return rows;
                    default:
                        return method.getReturnType() == int.class ? 0
                            : method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                }
            });
    }
}
