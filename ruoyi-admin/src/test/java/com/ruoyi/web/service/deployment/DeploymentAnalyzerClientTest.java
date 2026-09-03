package com.ruoyi.web.service.deployment;

import com.ruoyi.system.domain.DeploymentTask;
import com.ruoyi.system.mapper.DeploymentTaskMapper;
import com.ruoyi.system.mapper.DeploymentTaskAlgorithmMapper;
import com.ruoyi.system.service.impl.DeploymentTaskServiceImpl;
import com.ruoyi.web.controller.deployment.DeploymentController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.waring.domain.SvaServer;
import com.ruoyi.waring.domain.ZlmServer;
import com.ruoyi.waring.mapper.HDeviceMapper;
import com.ruoyi.waring.mapper.SvaServerMapper;
import com.ruoyi.waring.mapper.ZlmServerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

/** 验证分析器只在国标通道在线且已点播时消费 WVP/ZLM RTSP 地址。 */
class DeploymentAnalyzerClientTest {

    private DeploymentAnalyzerClient client;
    private HDeviceMapper deviceMapper;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        client = new DeploymentAnalyzerClient();
        deviceMapper = mock(HDeviceMapper.class);
        restTemplate = mock(RestTemplate.class);
        ZlmServerMapper zlmServerMapper = mock(ZlmServerMapper.class);
        SvaServerMapper svaServerMapper = mock(SvaServerMapper.class);
        ReflectionTestUtils.setField(client, "restTemplate", restTemplate);
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

    @Test
    void treatsMissingAnalyzerControlAsSuccessfulCancel() {
        DeploymentTask task = directTask();
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"code\":0,\"msg\":\"there is no such control\"}"));

        DeploymentAnalyzerClient.AnalyzerResult result = client.cancelControl(task);

        assertTrue(result.isSuccess());
        assertEquals("停止成功，布控已取消", result.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"code\":0,\"msg\":\"internal error\"}",
        "{\"code\":500,\"msg\":\"there is no such control\"}",
        "{\"msg\":\"there is no such control\"}",
        "{\"code\":null,\"msg\":\"there is no such control\"}",
        "{\"code\":\"invalid\",\"msg\":\"there is no such control\"}"
    })
    void doesNotTreatOtherFailuresOrMalformedCodesAsSuccessfulCancel(String body) {
        DeploymentTask task = directTask();
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok(body));

        assertFalse(client.cancelControl(task).isSuccess());
    }

    @Test
    void doesNotTreatMissingControlAsSuccessfulAdd() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"code\":0,\"msg\":\"there is no such control\"}"));

        DeploymentAnalyzerClient.AnalyzerResult result = ReflectionTestUtils.invokeMethod(client,
            "postJson", "http://127.0.0.1:19993/api/control/add", java.util.Collections.emptyMap(), "add");

        assertFalse(result.isSuccess());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DIRECT", "GB28181"})
    void stopControllerConvergesMissingControlToStopped(String sourceType) {
        DeploymentTask task = directTask();
        deviceMapper.selectDeviceByApeId(task.getDeviceId()).setStream_source_type(sourceType);
        task.setStatus("RUNNING");
        DeploymentTaskMapper taskMapper = mock(DeploymentTaskMapper.class);
        when(taskMapper.selectDeploymentTaskById(task.getDeploymentId())).thenReturn(task);
        when(taskMapper.updateDeploymentTaskStop(eq(task.getDeploymentId()), eq("STOPPED"), any(), any()))
            .thenAnswer(invocation -> {
                task.setStatus(invocation.getArgument(1));
                task.setStopTime(invocation.getArgument(2));
                return 1;
            });
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"code\":0,\"msg\":\"there is no such control\"}"));

        AjaxResult response = controller(taskMapper).stop(task.getDeploymentId());
        java.util.Map<?, ?> payload = (java.util.Map<?, ?>) response.get("data");
        java.util.Map<?, ?> data = (java.util.Map<?, ?>) payload.get("data");

        assertEquals(true, payload.get("success"));
        assertEquals("STOPPED", data.get("status"));
        org.junit.jupiter.api.Assertions.assertNotNull(data.get("stopTime"));
        verify(taskMapper).updateDeploymentTaskStop(eq(task.getDeploymentId()), eq("STOPPED"), any(), any());
    }

    @Test
    void stopControllerPreservesRunningStateOnAnalyzerFailure() {
        DeploymentTask task = directTask();
        task.setStatus("RUNNING");
        DeploymentTaskMapper taskMapper = mock(DeploymentTaskMapper.class);
        when(taskMapper.selectDeploymentTaskById(task.getDeploymentId())).thenReturn(task);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"code\":0,\"msg\":\"internal error\"}"));

        AjaxResult response = controller(taskMapper).stop(task.getDeploymentId());
        java.util.Map<?, ?> payload = (java.util.Map<?, ?>) response.get("data");
        java.util.Map<?, ?> data = (java.util.Map<?, ?>) payload.get("data");

        assertEquals(false, payload.get("success"));
        assertEquals("RUNNING", data.get("status"));
        verify(taskMapper, never()).updateDeploymentTaskStop(anyString(), anyString(), any(), any());
    }

    private DeploymentController controller(DeploymentTaskMapper taskMapper) {
        DeploymentTaskServiceImpl taskService = new DeploymentTaskServiceImpl();
        ReflectionTestUtils.setField(taskService, "deploymentTaskMapper", taskMapper);
        ReflectionTestUtils.setField(taskService, "deploymentTaskAlgorithmMapper", mock(DeploymentTaskAlgorithmMapper.class));
        DeploymentController controller = new DeploymentController();
        ReflectionTestUtils.setField(controller, "deploymentTaskService", taskService);
        ReflectionTestUtils.setField(controller, "deploymentAnalyzerClient", client);
        return controller;
    }

    private DeploymentTask directTask() {
        HDevice direct = new HDevice();
        direct.setApe_id("cam000001");
        direct.setStream_source_type("DIRECT");
        when(deviceMapper.selectDeviceByApeId("cam000001")).thenReturn(direct);
        DeploymentTask task = new DeploymentTask();
        task.setDeploymentId("control-restarted-analyzer");
        task.setDeviceId("cam000001");

        return task;
    }
}
