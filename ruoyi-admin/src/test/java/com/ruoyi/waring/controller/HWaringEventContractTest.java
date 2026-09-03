package com.ruoyi.waring.controller;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.service.IDeploymentTaskService;
import com.ruoyi.waring.domain.HWaring;
import com.ruoyi.waring.service.HWaringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HWaringEventContractTest {
    private HWaringController controller;
    private HWaringService warnings;

    @BeforeEach
    void setUp() {
        controller = new HWaringController();
        warnings = mock(HWaringService.class);
        ReflectionTestUtils.setField(controller, "hWaringService", warnings);
        ReflectionTestUtils.setField(controller, "deploymentTaskService", mock(IDeploymentTaskService.class));
    }

    @Test
    void analyzerSleepEventCreatesCanonicalSleepDutyWarning() {
        controller.consumeSvaDetectEvent(event("sleep", "start").toJSONString());

        ArgumentCaptor<HWaring> saved = ArgumentCaptor.forClass(HWaring.class);
        verify(warnings).insertWaring(saved.capture());
        assertEquals("SLEEP_DUTY", saved.getValue().getAlarm_type());
        assertEquals("睡岗告警", saved.getValue().getAlarm_type_name());
        assertEquals("sleep_duty", saved.getValue().getSva_behavior_type());
    }

    @Test
    void mediaCallbackPersistsGeneratedVideoTogetherWithImage() {
        existingWarning();
        when(warnings.updateSvaMediaFields(any())).thenReturn(1);
        JSONObject body = new JSONObject();
        body.put("alarm_id", "alarm-1");
        body.put("image_path", "https://media.example/alarm/frame.jpg");
        body.put("video_path", "https://media.example/alarm/clip.mp4");
        body.put("status", "success");

        AjaxResult result = controller.addFromSvaMediaCallback(body);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        ArgumentCaptor<HWaring> saved = ArgumentCaptor.forClass(HWaring.class);
        verify(warnings).updateSvaMediaFields(saved.capture());
        assertEquals(body.getString("video_path"), saved.getValue().getVideo_url());
        assertEquals(body.getString("video_path"), saved.getValue().getVideo_absolute_url());
        assertEquals(body.getString("image_path"), saved.getValue().getPicture_absolute_url());
    }

    @Test
    void lifecycleEndPersistsVideoAndDuration() {
        existingWarning();
        JSONObject body = event("sleep_duty", "end");
        body.put("videoPath", "https://media.example/alarm/finished.mp4");
        body.put("durationMs", 45000);

        controller.consumeSvaDetectEvent(body.toJSONString());

        ArgumentCaptor<HWaring> saved = ArgumentCaptor.forClass(HWaring.class);
        verify(warnings).updateSvaLifecycleWaring(saved.capture());
        assertEquals(body.getString("videoPath"), saved.getValue().getVideo_url());
        assertEquals(body.getString("videoPath"), saved.getValue().getVideo_absolute_url());
        assertEquals(45000L, saved.getValue().getDuration_ms());
        assertNotNull(saved.getValue().getEnd_time());
    }

    @Test
    void imageOnlyCallbackKeepsExistingVideoUntouched() {
        existingWarning();
        when(warnings.updateSvaMediaFields(any())).thenReturn(1);
        JSONObject body = new JSONObject();
        body.put("alarm_id", "alarm-1");
        body.put("image_path", "alarm/frame.jpg");
        controller.addFromSvaMediaCallback(body);

        ArgumentCaptor<HWaring> saved = ArgumentCaptor.forClass(HWaring.class);
        verify(warnings).updateSvaMediaFields(saved.capture());
        assertNull(saved.getValue().getVideo_url());
        assertNull(saved.getValue().getVideo_absolute_url());
    }

    private void existingWarning() {
        HWaring existing = new HWaring();
        existing.setId("alarm-1");
        existing.setAlarm_time("2026-09-03 10:00:00");
        when(warnings.selectWaringById("alarm-1")).thenReturn(existing);
    }

    private JSONObject event(String behavior, String state) {
        JSONObject body = new JSONObject();
        body.put("type", "detect.event");
        body.put("eventId", "alarm-1");
        body.put("behaviorType", behavior);
        body.put("eventState", state);
        body.put("timestampMs", 1788400845000L);
        body.put("startTimestampMs", 1788400800000L);
        return body;
    }
}
