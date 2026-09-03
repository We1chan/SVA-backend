package com.ruoyi.waring.service;

import com.ruoyi.waring.controller.HWaringController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 4：睡岗告警 (SLEEP_DUTY) 业务闭环——通过反射直接验证 HWaringController 中的
 * 告警类型元数据常量、行为类型识别与告警类型映射。
 *
 * 设计动机：
 * - 计划要求"使用 AI 组提供的回传样例"做告警接口测试；当前 AI 端尚未发出 sleep_duty
 *   事件，因此 controller 私有方法 resolveAlarmTypeMeta/normalizeBehaviorType 是
 *   唯一能在不依赖 SVA 服务的情况下被断言的边界。
 * - 选择反射而非 Spring 上下文：避免 @SpringBootTest 启动开销，加快 CI。
 * - 反射破坏封装但可接受——这是 controller 内部协议级别的契约测试。
 */
public class HWaringAlarmTypeTest {

    private static final String CONTROLLER = "com.ruoyi.waring.controller.HWaringController";

    @Test
    public void sleepDutyConstantsExposeStableContract() throws Exception {
        Class<?> cls = Class.forName(CONTROLLER);
        assertEquals("SLEEP_DUTY", readStaticString(cls, "SLEEP_DUTY_ALARM_TYPE"));
        assertEquals("睡岗告警", readStaticString(cls, "SLEEP_DUTY_ALARM_TYPE_NAME"));
        assertEquals("sleep_duty", readStaticString(cls, "SLEEP_DUTY_BEHAVIOR_TYPE"));
    }

    @Test
    public void normalizeBehaviorTypeAcceptsAnyCaseAndRejectsUnknown() throws Exception {
        Object controller = instantiateController();
        Method normalize = controller.getClass().getDeclaredMethod("normalizeBehaviorType", String.class);
        normalize.setAccessible(true);
        assertEquals("sleep_duty", normalize.invoke(controller, "sleep_duty"));
        assertEquals("sleep_duty", normalize.invoke(controller, "  SLEEP_DUTY  "));
        assertEquals("sleep_duty", normalize.invoke(controller, "SLEEP_DUTY"));
        assertEquals("sleep_duty", normalize.invoke(controller, "sleep"));
        assertEquals("sleep_duty", normalize.invoke(controller, "  SLEEP  "));
        assertEquals("", normalize.invoke(controller, "unknown_type"));
        assertEquals("", normalize.invoke(controller, (Object) null));
    }

    @Test
    public void resolveAlarmTypeMetaForSleepDutyReturnsSleepDutyMeta() throws Exception {
        Object controller = instantiateController();
        Method resolve = controller.getClass().getDeclaredMethod("resolveAlarmTypeMeta", String.class);
        resolve.setAccessible(true);
        Object meta = resolve.invoke(controller, "sleep_duty");
        assertNotNull(meta, "sleep_duty 必须解析到 AlarmTypeMeta");
        Field alarmType = meta.getClass().getDeclaredField("alarmType");
        Field alarmTypeName = meta.getClass().getDeclaredField("alarmTypeName");
        alarmType.setAccessible(true);
        alarmTypeName.setAccessible(true);
        assertEquals("SLEEP_DUTY", alarmType.get(meta));
        assertEquals("睡岗告警", alarmTypeName.get(meta));
    }

    @Test
    public void resolveAlarmTypeMetaForUnknownBehaviorReturnsNull() throws Exception {
        Object controller = instantiateController();
        Method resolve = controller.getClass().getDeclaredMethod("resolveAlarmTypeMeta", String.class);
        resolve.setAccessible(true);
        assertEquals(null, resolve.invoke(controller, "unknown_behavior"));
        assertEquals(null, resolve.invoke(controller, (Object) null));
    }

    @Test
    public void sleepDutyMetaIsDistinctFromAbsence() throws Exception {
        Object controller = instantiateController();
        Method resolve = controller.getClass().getDeclaredMethod("resolveAlarmTypeMeta", String.class);
        resolve.setAccessible(true);
        Object sleepMeta = resolve.invoke(controller, "sleep_duty");
        Object absenceMeta = resolve.invoke(controller, "absence");
        assertTrue(sleepMeta != null && absenceMeta != null);
        Field alarmType = sleepMeta.getClass().getDeclaredField("alarmType");
        Field alarmTypeName = sleepMeta.getClass().getDeclaredField("alarmTypeName");
        alarmType.setAccessible(true);
        alarmTypeName.setAccessible(true);
        String sleepType = (String) alarmType.get(sleepMeta);
        String sleepName = (String) alarmTypeName.get(sleepMeta);
        Field absenceType = absenceMeta.getClass().getDeclaredField("alarmType");
        Field absenceName = absenceMeta.getClass().getDeclaredField("alarmTypeName");
        absenceType.setAccessible(true);
        absenceName.setAccessible(true);
        assertTrue(!sleepType.equals(absenceType.get(absenceMeta)), "SLEEP_DUTY 不应与 SVA_ABSENCE 共享告警类型");
        assertTrue(!sleepName.equals(absenceName.get(absenceMeta)), "睡岗告警 不应与 离岗/缺席告警 共享告警名");
    }

    private static String readStaticString(Class<?> cls, String name) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return (String) f.get(null);
    }

    private static Object instantiateController() throws Exception {
        Class<?> cls = Class.forName(CONTROLLER);
        // 跳过 Spring 注入：直接 newInstance()，因为本测试只调静态行为映射方法。
        java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    // 抑制未使用警告—— HWaringController 仅做类型引用以防包重构导致测试静默失败。
    @SuppressWarnings("unused")
    private Class<?> ensureControllerImported() {
        return HWaringController.class;
    }
}
