package com.ruoyi.web.service.deployment;

import java.util.Arrays;
import com.ruoyi.system.domain.DeploymentTask;
import com.ruoyi.system.service.IDeploymentTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentRecoveryServiceTest
{
    private DeploymentRecoveryService service;
    private IDeploymentTaskService taskService;
    private DeploymentAnalyzerClient analyzerClient;

    @BeforeEach
    void setUp()
    {
        service = new DeploymentRecoveryService();
        taskService = mock(IDeploymentTaskService.class);
        analyzerClient = mock(DeploymentAnalyzerClient.class);
        ReflectionTestUtils.setField(service, "deploymentTaskService", taskService);
        ReflectionTestUtils.setField(service, "deploymentAnalyzerClient", analyzerClient);
    }

    @Test
    void restoresEveryPersistedRunningDeployment()
    {
        DeploymentTask first = new DeploymentTask();
        first.setDeploymentId("control-one");
        DeploymentTask second = new DeploymentTask();
        second.setDeploymentId("control-two");
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
}
