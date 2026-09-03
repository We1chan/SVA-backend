package com.ruoyi.web.service.deployment;

import java.util.List;
import com.ruoyi.system.domain.DeploymentTask;
import com.ruoyi.system.service.IDeploymentTaskService;
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
                DeploymentAnalyzerClient.AnalyzerResult result = deploymentAnalyzerClient.ensureControl(task);
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
}
