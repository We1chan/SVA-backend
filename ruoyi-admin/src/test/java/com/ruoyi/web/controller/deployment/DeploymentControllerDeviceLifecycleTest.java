package com.ruoyi.web.controller.deployment;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.domain.DeploymentTask;
import com.ruoyi.system.service.IDeploymentTaskService;
import com.ruoyi.web.service.deployment.DeploymentAnalyzerClient;
import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.waring.service.HDeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证布控按钮和实际视频源生命周期保持一致。 */
class DeploymentControllerDeviceLifecycleTest
{
    private DeploymentController controller;
    private IDeploymentTaskService taskService;
    private DeploymentAnalyzerClient analyzerClient;
    private HDeviceService deviceService;

    @BeforeEach
    void setUp()
    {
        controller = new DeploymentController();
        taskService = mock(IDeploymentTaskService.class);
        analyzerClient = mock(DeploymentAnalyzerClient.class);
        deviceService = mock(HDeviceService.class);
        ReflectionTestUtils.setField(controller, "deploymentTaskService", taskService);
        ReflectionTestUtils.setField(controller, "deploymentAnalyzerClient", analyzerClient);
        ReflectionTestUtils.setField(controller, "hDeviceService", deviceService);
    }

    @Test
    void startDeploymentStartsStoppedVideoSourceBeforeAnalyzer()
    {
        DeploymentTask task = task("control-one", "device-one");
        HDevice device = device("device-one", "1", "STOPPED");
        when(taskService.selectDeploymentTaskById("control-one")).thenReturn(task);
        when(taskService.startDeploymentTask("control-one")).thenReturn(1);
        when(deviceService.selectDeviceByApeId("device-one")).thenReturn(device);
        when(deviceService.startMonitor("device-one")).thenReturn(1);
        when(analyzerClient.addControl(org.mockito.ArgumentMatchers.eq(task), anyString()))
            .thenReturn(DeploymentAnalyzerClient.AnalyzerResult.ok("started"));

        AjaxResult result = controller.start("control-one");

        assertEquals(200, result.get("code"));
        verify(deviceService).startMonitor("device-one");
        verify(analyzerClient).addControl(org.mockito.ArgumentMatchers.eq(task), anyString());
        verify(taskService).startDeploymentTask("control-one");
    }

    @Test
    void startDeploymentRejectsOfflineDeviceWithoutCallingAnalyzer()
    {
        DeploymentTask task = task("control-offline", "device-offline");
        when(taskService.selectDeploymentTaskById("control-offline")).thenReturn(task);
        when(deviceService.selectDeviceByApeId("device-offline"))
            .thenReturn(device("device-offline", "0", "STOPPED"));

        AjaxResult result = controller.start("control-offline");

        java.util.Map<?, ?> payload = (java.util.Map<?, ?>) result.get("data");
        assertEquals(false, payload.get("success"));
        verify(deviceService, never()).startMonitor(anyString());
        verify(analyzerClient, never()).addControl(org.mockito.ArgumentMatchers.any(), anyString());
    }

    private DeploymentTask task(String deploymentId, String deviceId)
    {
        DeploymentTask task = new DeploymentTask();
        task.setDeploymentId(deploymentId);
        task.setDeviceId(deviceId);
        task.setGeometryConfig("{\"regions\":[{\"primary\":true,\"points\":["
            + "{\"x\":0.1,\"y\":0.1},{\"x\":0.9,\"y\":0.1},"
            + "{\"x\":0.9,\"y\":0.9},{\"x\":0.1,\"y\":0.9}]}]}");
        return task;
    }

    private HDevice device(String apeId, String online, String monitorStatus)
    {
        HDevice device = new HDevice();
        device.setApe_id(apeId);
        device.setIs_online(online);
        device.setMonitor_status(monitorStatus);
        return device;
    }
}
