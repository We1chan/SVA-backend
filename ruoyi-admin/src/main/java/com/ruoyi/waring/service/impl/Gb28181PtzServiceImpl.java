package com.ruoyi.waring.service.impl;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.waring.domain.Gb28181PtzCommand;
import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.waring.mapper.HDeviceMapper;
import com.ruoyi.waring.service.Gb28181PtzService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通过 WVP 下发 GB28181 DeviceControl/PTZCmd。
 *
 * <p>浏览器只提交受限的语义化指令；本服务负责业务设备校验、速度换算以及
 * 服务端安全停止。HTTP 成功仅表示 WVP 已接受下发，不代表机械动作已完成。</p>
 */
@Service
public class Gb28181PtzServiceImpl implements Gb28181PtzService {

    private static final Logger log = LoggerFactory.getLogger(Gb28181PtzServiceImpl.class);
    private static final Set<String> COMMANDS = Set.of(
            "left", "right", "up", "down", "upleft", "upright",
            "downleft", "downright", "zoomin", "zoomout", "stop");

    @Resource
    private HDeviceMapper hDeviceMapper;

    @Value("${gb28181.enabled:false}")
    private boolean enabled;

    @Value("${gb28181.wvp-base-url:http://127.0.0.1:18080}")
    private String wvpBaseUrl;

    @Value("${gb28181.ptz-timeout-ms:3000}")
    private int requestTimeoutMs;

    @Value("${gb28181.ptz-safety-stop-ms:1500}")
    private long safetyStopMs;

    private final RestTemplate restTemplate = new RestTemplate();
    private final Map<String, ScheduledFuture<?>> pendingStops = new ConcurrentHashMap<>();
    private final Map<String, HDevice> pendingStopDevices = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> generations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService stopExecutor = Executors.newSingleThreadScheduledExecutor(
            new DaemonThreadFactory());

    @PostConstruct
    private void configureHttpClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(requestTimeoutMs);
        requestFactory.setReadTimeout(requestTimeoutMs);
        restTemplate.setRequestFactory(requestFactory);
    }

    @PreDestroy
    private void shutdown() {
        // A graceful backend restart must not strand a camera in continuous motion.
        pendingStopDevices.forEach((apeId, device) -> {
            try {
                dispatch(device, "stop", 0, 0, 0);
            } catch (RuntimeException ex) {
                log.warn("GB28181 云台关闭前停止失败, apeId={}, message={}", apeId, ex.getMessage());
            }
        });
        pendingStopDevices.clear();
        pendingStops.clear();
        stopExecutor.shutdownNow();
    }

    @Override
    public Map<String, Object> control(String apeId, Gb28181PtzCommand request) {
        if (!enabled) {
            throw new ServiceException("GB28181 云台控制未启用");
        }
        if (StringUtils.isBlank(apeId)) {
            throw new ServiceException("apeId 不能为空");
        }
        if (request == null || StringUtils.isBlank(request.getCommand())) {
            throw new ServiceException("云台控制指令不能为空");
        }

        HDevice device = hDeviceMapper.selectDeviceByApeId(apeId);
        validateDevice(device);

        String command = request.getCommand().trim().toLowerCase(Locale.ROOT);
        if (!COMMANDS.contains(command)) {
            throw new ServiceException("不支持的云台控制指令: " + request.getCommand());
        }

        int panSpeed = percent(request.getPanSpeed(), 50, "水平速度");
        int tiltSpeed = percent(request.getTiltSpeed(), 50, "垂直速度");
        int zoomSpeed = percent(request.getZoomSpeed(), 50, "变倍速度");
        long generation = generations.computeIfAbsent(apeId, ignored -> new AtomicLong()).incrementAndGet();
        cancelPendingStop(apeId);

        boolean moving = !"stop".equals(command);
        boolean dispatched = false;
        try {
            dispatch(device, command, panSpeed, tiltSpeed, zoomSpeed);
            dispatched = true;
        } finally {
            // 即使请求在响应阶段失败，也可能已经到达摄像机；移动命令始终安排兜底 STOP。
            // 显式 STOP 下发失败时也补一次延迟 STOP，不能因取消旧定时任务而失去保护。
            if (moving || !dispatched) {
                scheduleSafetyStop(apeId, device, generation);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("command", command);
        result.put("safetyStopMs", moving ? safetyStopMs : 0);
        result.put("message", "云台指令已下发");
        return result;
    }

    private void validateDevice(HDevice device) {
        if (device == null) {
            throw new ServiceException("设备不存在");
        }
        boolean gb28181 = "GB28181".equalsIgnoreCase(device.getDevice_type())
                || "GB28181".equalsIgnoreCase(device.getStream_source_type());
        if (!gb28181) {
            throw new ServiceException("仅 GB28181 设备支持云台控制");
        }
        if (!"1".equals(device.getIs_online())) {
            throw new ServiceException("国标设备当前离线，无法控制云台");
        }
        if (StringUtils.isBlank(device.getGb_device_id()) || StringUtils.isBlank(device.getGb_channel_id())) {
            throw new ServiceException("国标设备或通道编号缺失");
        }
    }

    private int percent(Integer value, int defaultValue, String label) {
        int normalized = value == null ? defaultValue : value;
        if (normalized < 0 || normalized > 100) {
            throw new ServiceException(label + "必须在 0-100 之间");
        }
        return normalized;
    }

    private void dispatch(HDevice device, String command, int panSpeed, int tiltSpeed, int zoomSpeed) {
        int wvpPanSpeed = scale(panSpeed, 255);
        int wvpTiltSpeed = scale(tiltSpeed, 255);
        int wvpZoomSpeed = scale(zoomSpeed, 15);
        if ("stop".equals(command)) {
            wvpPanSpeed = 0;
            wvpTiltSpeed = 0;
            wvpZoomSpeed = 0;
        }

        URI uri = UriComponentsBuilder.fromUriString(normalizedBaseUrl())
                .pathSegment("api", "front-end", "ptz", device.getGb_device_id(), device.getGb_channel_id())
                .queryParam("command", command)
                .queryParam("horizonSpeed", wvpPanSpeed)
                .queryParam("verticalSpeed", wvpTiltSpeed)
                .queryParam("zoomSpeed", wvpZoomSpeed)
                .build()
                .toUri();
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new ServiceException("WVP 云台控制失败: HTTP " + response.getStatusCode().value());
            }
        } catch (HttpStatusCodeException ex) {
            throw new ServiceException("WVP 云台控制失败: HTTP " + ex.getStatusCode().value());
        } catch (RestClientException ex) {
            throw new ServiceException("WVP 云台控制失败: " + ex.getMessage());
        }
    }

    private int scale(int percent, int maximum) {
        return (int) Math.round(percent / 100.0D * maximum);
    }

    private void scheduleSafetyStop(String apeId, HDevice device, long generation) {
        long delay = Math.max(250L, safetyStopMs);
        pendingStopDevices.put(apeId, device);
        ScheduledFuture<?> future = stopExecutor.schedule(() -> {
            AtomicLong current = generations.get(apeId);
            if (current == null || current.get() != generation) {
                return;
            }
            try {
                dispatch(device, "stop", 0, 0, 0);
            } catch (RuntimeException ex) {
                log.warn("GB28181 云台安全停止失败, apeId={}, message={}", apeId, ex.getMessage());
            } finally {
                AtomicLong latest = generations.get(apeId);
                if (latest != null && latest.get() == generation) {
                    pendingStops.remove(apeId);
                    pendingStopDevices.remove(apeId);
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
        pendingStops.put(apeId, future);
    }

    private void cancelPendingStop(String apeId) {
        ScheduledFuture<?> future = pendingStops.remove(apeId);
        if (future != null) {
            future.cancel(false);
        }
        pendingStopDevices.remove(apeId);
    }

    private String normalizedBaseUrl() {
        String value = wvpBaseUrl == null ? "" : wvpBaseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "gb28181-ptz-safety-stop");
            thread.setDaemon(true);
            return thread;
        }
    }
}
