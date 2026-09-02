package com.ruoyi.waring.task;

import com.ruoyi.waring.service.Gb28181SyncService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * GB28181 设备目录的定时同步入口。
 *
 * <p>模块：流媒体协议组 / 设备同步与在线状态。调度器只负责触发与记录结果，
 * 业务规则集中在 {@link Gb28181SyncService} 中。</p>
 */
@Component("gb28181SyncTask")
public class Gb28181SyncTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(Gb28181SyncTaskScheduler.class);

    @Resource
    private Gb28181SyncService gb28181SyncService;

    @Value("${gb28181.enabled:false}")
    private boolean enabled;

    @Scheduled(
            initialDelayString = "${gb28181.sync-initial-delay-ms:30000}",
            fixedDelayString = "${gb28181.sync-fixed-delay-ms:15000}")
    public void scheduledSync() {
        if (!enabled) {
            return;
        }

        try {
            Map<String, Object> result = gb28181SyncService.syncDevices();
            log.debug("GB28181 device synchronization completed: {}", result);
        } catch (Exception ex) {
            // 单次 WVP 抖动不能终止 Spring 调度线程，下一周期会自动重试完整快照。
            log.warn("GB28181 device synchronization failed: {}", ex.getMessage());
        }
    }
}
