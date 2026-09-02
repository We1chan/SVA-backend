package com.ruoyi.waring.task;

import com.ruoyi.waring.service.Gb28181SyncService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

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
            log.warn("GB28181 device synchronization failed: {}", ex.getMessage());
        }
    }
}
