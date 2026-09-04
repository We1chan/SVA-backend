package com.ruoyi.web.service.deployment;

import java.util.List;
import com.ruoyi.system.domain.DeploymentTask;
import com.ruoyi.system.service.IDeploymentTaskService;
import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.waring.service.HDeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Restores persisted RUNNING deployments after the analyzer loses memory state. */
@Service
public class DeploymentRecoveryService
{
    private static final Logger log = LoggerFactory.getLogger(DeploymentRecoveryService.class);

    @Autowired
    private IDeploymentTaskService deploymentTaskService;

    @Autowired
    private DeploymentAnalyzerClient deploymentAnalyzerClient;

    @Autowired
    private HDeviceService hDeviceService;

    @Scheduled(
        initialDelayString = "${easysva.deployment.recovery-initial-delay-ms:5000}",
        fixedDelayString = "${easysva.deployment.recovery-fixed-delay-ms:15000}")
    public void restoreRunningDeployments()
    {
        List<DeploymentTask> tasks = deploymentTaskService.selectDeploymentTaskList("RUNNING", null, null);
        if (tasks == null || tasks.isEmpty())
        {
            return;
        }

        for (DeploymentTask task : tasks)
        {
            if (task == null)
            {
                continue;
            }
            try
            {
                HDevice device = hDeviceService.selectDeviceByApeId(task.getDeviceId());
                if (device == null || !"1".equals(device.getIs_online()))
                {
                    log.debug("布控等待设备上线, deploymentId={}, deviceId={}",
                        task.getDeploymentId(), task.getDeviceId());
                    continue;
                }
                if (!"RUNNING".equalsIgnoreCase(device.getMonitor_status()))
                {
                    log.info("运行中布控的视频源未启动，自动恢复, deploymentId={}, deviceId={}, monitorStatus={}",
                        task.getDeploymentId(), task.getDeviceId(), device.getMonitor_status());
                    hDeviceService.startMonitor(task.getDeviceId());
                }

                DeploymentAnalyzerClient.AnalyzerResult result = deploymentAnalyzerClient.ensureControl(task);
                if (!result.isSuccess() && isMediaRecoveryFailure(result))
                {
                    // GB28181 disconnects invalidate the old WVP playback URL. Re-INVITE
                    // the channel once it is online again, then rebuild the analyzer control.
                    hDeviceService.startMonitor(task.getDeviceId());
                    result = deploymentAnalyzerClient.ensureControl(task);
                }
                if (result.isSuccess())
                {
                    log.debug("布控自动恢复检查成功, deploymentId={}, message={}",
                        task.getDeploymentId(), result.getMessage());
                }
                else
                {
                    log.warn("布控自动恢复失败, deploymentId={}, message={}, detail={}",
                        task.getDeploymentId(), result.getMessage(), result.getDetailMessage());
                }
            }
            catch (Exception ex)
            {
                log.warn("布控自动恢复异常, deploymentId={}", task.getDeploymentId(), ex);
            }
        }
    }

    private boolean isMediaRecoveryFailure(DeploymentAnalyzerClient.AnalyzerResult result)
    {
        String text = (result.getMessage() == null ? "" : result.getMessage()) + " "
            + (result.getDetailMessage() == null ? "" : result.getDetailMessage());
        String normalized = text.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("媒体流") || normalized.contains("播放地址")
            || normalized.contains("buildstreamurl") || normalized.contains("pull stream");
    }
}
