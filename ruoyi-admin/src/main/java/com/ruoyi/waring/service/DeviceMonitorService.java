package com.ruoyi.waring.service;

import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.DeploymentTask;
import com.ruoyi.system.service.IDeploymentTaskService;
import com.ruoyi.waring.domain.DeviceMonitorResult;
import com.ruoyi.waring.domain.HDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.List;

/**
 * 设备监控启停的统一编排服务。
 *
 * <p>将两条路径（启动/停止）共享的“查设备 → 执行动作 → 读取状态 → 返回业务结果”
 * 收敛到一处，Controller 只负责权限、路由与响应包装。失败后统一做状态重读，
 * 重读若再次失败则保留已知设备快照，不让二次异常覆盖最初的业务失败原因。</p>
 */
@Service
public class DeviceMonitorService {

    private static final Logger log = LoggerFactory.getLogger(DeviceMonitorService.class);

    private final HDeviceService hDeviceService;
    private final IDeploymentTaskService deploymentTaskService;

    @Autowired
    public DeviceMonitorService(HDeviceService hDeviceService,
                                IDeploymentTaskService deploymentTaskService) {
        this.hDeviceService = hDeviceService;
        this.deploymentTaskService = deploymentTaskService;
    }

    /** Test/support constructor for callers that do not need deployment guards. */
    public DeviceMonitorService(HDeviceService hDeviceService) {
        this(hDeviceService, null);
    }

    /** 启动设备实时监控。 */
    public DeviceMonitorResult start(String apeId) {
        return execute("启动", apeId, true);
    }

    /** 停止设备实时监控。 */
    public DeviceMonitorResult stop(String apeId) {
        return execute("停止", apeId, false);
    }

    private DeviceMonitorResult execute(String action, String apeId, boolean start) {
        HDevice snapshot = hDeviceService.selectDeviceByApeId(apeId);
        if (snapshot == null) {
            return DeviceMonitorResult.fail("设备不存在", null);
        }

        if (!start) {
            int runningDeployments = countRunningDeployments(apeId);
            if (runningDeployments > 0) {
                return DeviceMonitorResult.fail(
                    "该视频源仍有" + runningDeployments + "个运行中的布控，请先在布控管理中停止布控",
                    snapshot);
            }
        }

        try {
            int rows = start ? hDeviceService.startMonitor(apeId) : hDeviceService.stopMonitor(apeId);
            if (rows <= 0) {
                HDevice latest = safeRead(apeId, snapshot);
                return DeviceMonitorResult.fail(action + "监控失败", latest);
            }
            HDevice latest = safeRead(apeId, snapshot);
            return DeviceMonitorResult.ok(latest);
        } catch (Exception ex) {
            HDevice latest = safeRead(apeId, snapshot);
            return DeviceMonitorResult.fail(resolveMessage(action, ex), latest);
        }
    }

    private int countRunningDeployments(String apeId) {
        if (deploymentTaskService == null || StringUtils.isBlank(apeId)) {
            return 0;
        }
        List<DeploymentTask> tasks = deploymentTaskService.selectDeploymentTaskList("RUNNING", null, null);
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (DeploymentTask task : tasks) {
            if (task != null && apeId.equals(task.getDeviceId())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 失败后的状态重读。若重读再次抛出异常，记录日志并返回传入的快照，
     * 保证二次异常不会覆盖最初的业务失败结果。
     */
    private HDevice safeRead(String apeId, HDevice snapshot) {
        try {
            return hDeviceService.selectDeviceByApeId(apeId);
        } catch (Exception ex) {
            log.warn("监控动作后重读设备状态失败，保留已知设备快照。apeId={}", apeId, ex);
            return snapshot;
        }
    }

    /**
     * 从异常推导面向前端的简短提示。保留已知中文映射，未知业务异常返回原始信息。
     * 匹配使用 {@link Locale#ROOT} 避免受默认语言环境影响。
     */
    String resolveMessage(String action, Exception ex) {
        String fallback = action + "监控失败";
        if (ex == null) {
            return fallback;
        }

        String message = ex.getMessage();
        if (StringUtils.isEmpty(message)) {
            return fallback;
        }

        String lowerMessage = message.toLowerCase(Locale.ROOT);
        if (lowerMessage.contains("pull stream connect error")) {
            return "读取视频流失败，请确认设备启动了视频流";
        }
        if (lowerMessage.contains("push stream connect error")) {
            return "推送失败，请稍后再试！";
        }
        if (lowerMessage.contains("already exists")) {
            return "设备监控已经启动过";
        }
        if (lowerMessage.contains("timeout")) {
            return "连接超时";
        }
        return message;
    }
}
