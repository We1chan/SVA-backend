package com.ruoyi.web.service.deployment;

import java.util.Arrays;
import com.ruoyi.system.domain.DeploymentTask;
import com.ruoyi.system.service.IDeploymentTaskService;
import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.waring.service.HDeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class DeploymentRecoveryServiceTest
{
    private DeploymentRecoveryService service;
    private IDeploymentTaskService taskService;
    private DeploymentAnalyzerClient analyzerClient;
    private HDeviceService deviceService;

    @BeforeEach
    void setUp()
    {
        service = new DeploymentRecoveryService();
        taskService = mock(IDeploymentTaskService.class);
        analyzerClient = mock(DeploymentAnalyzerClient.class);
        deviceService = mock(HDeviceService.class);
        ReflectionTestUtils.setField(service, "deploymentTaskService", taskService);
        ReflectionTestUtils.setField(service, "deploymentAnalyzerClient", analyzerClient);
        ReflectionTestUtils.setField(service, "hDeviceService", deviceService);
    }

    @Test
    void restoresEveryPersistedRunningDeployment()
    {
        DeploymentTask first = new DeploymentTask();
        first.setDeploymentId("control-one");
        first.setDeviceId("device-one");
        DeploymentTask second = new DeploymentTask();
        second.setDeploymentId("control-two");
        second.setDeviceId("device-two");
        when(deviceService.selectDeviceByApeId("device-one")).thenReturn(runningDevice("device-one"));
        when(deviceService.selectDeviceByApeId("device-two")).thenReturn(runningDevice("device-two"));
        when(taskService.selectDeploymentTaskList("RUNNING", null, null))
            .thenReturn(Arrays.asList(first, second));
        when(analyzerClient.ensureControl(first))
            .thenReturn(DeploymentAnalyzerClient.AnalyzerResult.ok("already running"));
        when(analyzerClient.ensureControl(second))
            .thenReturn(DeploymentAnalyzerClient.AnalyzerResult.ok("restored"));

        service.restoreRunningDeployments();

        verify(analyzerClient).ensureControl(first);
        verify(analyzerClient).ensureControl(second);
    }

    @Test
    void skipsRecoveryWhileDeviceIsOffline()
    {
        DeploymentTask task = task("control-offline", "device-offline");
        HDevice device = runningDevice("device-offline");
        device.setIs_online("0");
        when(taskService.selectDeploymentTaskList("RUNNING", null, null))
            .thenReturn(Arrays.asList(task));
        when(deviceService.selectDeviceByApeId("device-offline")).thenReturn(device);

        service.restoreRunningDeployments();

        verify(analyzerClient, never()).ensureControl(task);
    }

    @Test
    void reinvitesMediaAndRetriesRecoveryAfterGbReconnect()
    {
        DeploymentTask task = task("control-reconnect", "device-gb");
        when(taskService.selectDeploymentTaskList("RUNNING", null, null))
            .thenReturn(Arrays.asList(task));
        when(deviceService.selectDeviceByApeId("device-gb")).thenReturn(runningDevice("device-gb"));
        when(analyzerClient.ensureControl(task))
            .thenReturn(DeploymentAnalyzerClient.AnalyzerResult.fail("设备无可播放媒体流", "buildStreamUrl返回空"))
            .thenReturn(DeploymentAnalyzerClient.AnalyzerResult.ok("restored"));

        service.restoreRunningDeployments();

        verify(deviceService).startMonitor("device-gb");
        verify(analyzerClient, org.mockito.Mockito.times(2)).ensureControl(task);
    }

    @Test
    void startsStoppedSourceForPersistedRunningDeployment()
    {
        DeploymentTask task = task("control-stopped-source", "device-stopped");
        HDevice device = runningDevice("device-stopped");
        device.setMonitor_status("STOPPED");
        when(taskService.selectDeploymentTaskList("RUNNING", null, null))
            .thenReturn(Arrays.asList(task));
        when(deviceService.selectDeviceByApeId("device-stopped")).thenReturn(device);
        when(deviceService.startMonitor("device-stopped")).thenReturn(1);
        when(analyzerClient.ensureControl(task))
            .thenReturn(DeploymentAnalyzerClient.AnalyzerResult.ok("restored"));

        service.restoreRunningDeployments();

        verify(deviceService).startMonitor("device-stopped");
        verify(analyzerClient).ensureControl(task);
    }

    private DeploymentTask task(String deploymentId, String deviceId)
    {
        DeploymentTask task = new DeploymentTask();
        task.setDeploymentId(deploymentId);
        task.setDeviceId(deviceId);
        return task;
    }

    private HDevice runningDevice(String apeId)
    {
        HDevice device = new HDevice();
        device.setApe_id(apeId);
        device.setIs_online("1");
        device.setMonitor_status("RUNNING");
        return device;
    }
}
