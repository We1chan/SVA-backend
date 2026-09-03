package com.ruoyi.waring.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.waring.mapper.HDeviceMapper;
import com.ruoyi.waring.service.Gb28181SyncService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将 WVP 的 GB28181 设备目录同步为 easySVA 的通道级设备记录。
 *
 * <p>模块：流媒体协议组 / 设备同步与在线状态。同步采用完整快照语义：先读取
 * WVP，再在同一事务内更新本地状态；DIRECT/PLATFORM 设备不会被本服务修改。</p>
 */
@Service
public class Gb28181SyncServiceImpl implements Gb28181SyncService {

    private static final Logger log = LoggerFactory.getLogger(Gb28181SyncServiceImpl.class);
    private static final int PAGE_SIZE = 200;
    private static final String SOURCE_TYPE_GB28181 = "GB28181";

    @Resource
    private HDeviceMapper hDeviceMapper;

    @Value("${gb28181.enabled:false}")
    private boolean enabled;

    @Value("${gb28181.wvp-base-url:http://127.0.0.1:18080}")
    private String wvpBaseUrl;

    @Value("${gb28181.media-server-id:easysva-gb28181}")
    private String defaultMediaServerId;

    @Value("${gb28181.default-org-index:103}")
    private String defaultOrgIndex;

    @Value("${gb28181.default-org-name:研发部门}")
    private String defaultOrgName;

    private final RestTemplate restTemplate = new RestTemplate();
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    @PostConstruct
    private void configureHttpClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3000);
        requestFactory.setReadTimeout(10000);
        restTemplate.setRequestFactory(requestFactory);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> syncDevices() {
        if (!enabled) {
            throw new ServiceException("GB28181 同步未启用");
        }
        if (!syncing.compareAndSet(false, true)) {
            throw new ServiceException("GB28181 设备同步正在进行中");
        }

        long startedAt = System.currentTimeMillis();
        try {
            String syncTime = DateUtils.getTime();
            SyncSnapshot snapshot = fetchSnapshot(syncTime);

            // 快照中缺失的旧通道应视为离线：先统一离线，再用本轮快照恢复实际状态。
            hDeviceMapper.markAllGb28181DevicesOffline();
            for (HDevice device : snapshot.channels) {
                hDeviceMapper.upsertGb28181Device(device);
            }
            // 离线通道的旧播放 URL 已失效，必须清除，避免前端或分析器继续使用陈旧会话。
            hDeviceMapper.clearOfflineGb28181Playback();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deviceCount", snapshot.deviceCount);
            result.put("channelCount", snapshot.channels.size());
            result.put("onlineChannelCount", snapshot.onlineChannelCount);
            result.put("offlineChannelCount", snapshot.channels.size() - snapshot.onlineChannelCount);
            result.put("skippedChannelCount", snapshot.skippedChannelCount);
            result.put("syncedAt", syncTime);
            result.put("elapsedMs", System.currentTimeMillis() - startedAt);
            return result;
        } finally {
            syncing.set(false);
        }
    }

    private SyncSnapshot fetchSnapshot(String syncTime) {
        SyncSnapshot snapshot = new SyncSnapshot();
        int pageNumber = 1;
        while (true) {
            PageData devicePage = fetchPage(buildDevicesUri(pageNumber));
            for (JsonNode parentDevice : devicePage.items) {
                String parentDeviceId = text(parentDevice, "deviceId");
                if (StringUtils.isBlank(parentDeviceId)) {
                    continue;
                }
                snapshot.deviceCount++;
                appendChannels(snapshot, parentDevice, parentDeviceId, syncTime);
            }
            if (pageNumber >= devicePage.pages) {
                break;
            }
            pageNumber++;
        }
        return snapshot;
    }

    private void appendChannels(SyncSnapshot snapshot, JsonNode parentDevice,
                                String parentDeviceId, String syncTime) {
        int pageNumber = 1;
        while (true) {
            PageData channelPage = fetchPage(buildChannelsUri(parentDeviceId, pageNumber));
            for (JsonNode channel : channelPage.items) {
                Integer channelType = integer(channel, "channelType");
                // WVP 的非零 channelType 表示非视频通道，不应作为摄像机写入设备表。
                if (channelType != null && channelType != 0) {
                    snapshot.skippedChannelCount++;
                    continue;
                }

                String channelId = text(channel, "deviceId", "gbDeviceId");
                if (StringUtils.isBlank(channelId)) {
                    snapshot.skippedChannelCount++;
                    continue;
                }

                HDevice device = buildDevice(parentDevice, channel, parentDeviceId, channelId, syncTime);
                snapshot.channels.add(device);
                if ("1".equals(device.getIs_online())) {
                    snapshot.onlineChannelCount++;
                }
            }
            if (pageNumber >= channelPage.pages) {
                break;
            }
            pageNumber++;
        }
    }

    private HDevice buildDevice(JsonNode parentDevice, JsonNode channel,
                                String parentDeviceId, String channelId, String syncTime) {
        boolean parentOnline = booleanValue(parentDevice, "onLine", "online", "status");
        String channelStatus = text(channel, "status", "gbStatus");
        boolean channelOnline = StringUtils.isBlank(channelStatus)
                ? parentOnline
                : parentOnline && isOnlineStatus(channelStatus);

        HDevice device = new HDevice();
        device.setApe_id(buildApeId(parentDeviceId, channelId));
        device.setName(firstNotBlank(text(channel, "name", "gbName"),
                text(parentDevice, "name"), channelId));
        device.setDevice_type(SOURCE_TYPE_GB28181);
        device.setStream_source_type(SOURCE_TYPE_GB28181);
        device.setGb_device_id(parentDeviceId);
        device.setGb_channel_id(channelId);

        String mediaServerId = text(parentDevice, "mediaServerId");
        if (StringUtils.isBlank(mediaServerId) || "auto".equalsIgnoreCase(mediaServerId)) {
            mediaServerId = defaultMediaServerId;
        }
        device.setGb_media_server_id(mediaServerId);
        device.setGb_stream_id(text(channel, "streamId"));
        device.setGb_last_sync_time(syncTime);
        device.setResource_type(SOURCE_TYPE_GB28181);
        device.setIp_addr(firstNotBlank(text(channel, "ipAddress", "gbIpAddress"),
                text(parentDevice, "ip")));
        device.setPort(firstNotNull(integer(channel, "port", "gbPort"),
                integer(parentDevice, "port")));
        device.setOrg_index(defaultOrgIndex);
        device.setOrg_name(defaultOrgName);
        device.setPlace_code(text(channel, "civilCode", "gbCivilCode"));
        device.setPlace(text(channel, "address", "gbAddress"));
        device.setIs_online(channelOnline ? "1" : "0");
        String manufacturer = firstNotBlank(text(channel, "manufacturer", "gbManufacturer"),
                text(parentDevice, "manufacturer"));
        device.setProducer(manufacturer);
        device.setProducer_name(manufacturer);
        device.setParent_code(parentDeviceId);
        device.setMonitor_status("STOPPED");
        device.setCreate_time(syncTime);
        device.setUpdate_time(syncTime);
        return device;
    }

    private PageData fetchPage(URI uri) {
        JsonNode response;
        try {
            response = restTemplate.getForObject(uri, JsonNode.class);
        } catch (RestClientException ex) {
            throw new ServiceException("访问 WVP 失败: " + ex.getMessage());
        }
        if (response == null || response.isNull()) {
            throw new ServiceException("WVP 返回空响应");
        }

        if (response.has("code")) {
            int code = response.path("code").asInt(-1);
            if (code != 0 && code != 200) {
                throw new ServiceException("WVP 返回错误: " + response.path("msg").asText("unknown"));
            }
        }

        JsonNode page = response.has("data") ? response.path("data") : response;
        JsonNode list = page.path("list");
        if (!list.isArray()) {
            return new PageData(Collections.emptyList(), 0);
        }

        List<JsonNode> items = new ArrayList<>();
        list.forEach(items::add);
        int pages = page.path("pages").asInt(items.isEmpty() ? 0 : 1);
        return new PageData(items, pages);
    }

    private URI buildDevicesUri(int pageNumber) {
        return UriComponentsBuilder.fromUriString(normalizedBaseUrl())
                .pathSegment("api", "device", "query", "devices")
                .queryParam("page", pageNumber)
                .queryParam("count", PAGE_SIZE)
                .build()
                .toUri();
    }

    private URI buildChannelsUri(String deviceId, int pageNumber) {
        return UriComponentsBuilder.fromUriString(normalizedBaseUrl())
                .pathSegment("api", "device", "query", "devices", deviceId, "channels")
                .queryParam("page", pageNumber)
                .queryParam("count", PAGE_SIZE)
                .build()
                .toUri();
    }

    private String normalizedBaseUrl() {
        String value = wvpBaseUrl == null ? "" : wvpBaseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String buildApeId(String deviceId, String channelId) {
        String source = deviceId + ":" + channelId;
        // 同一国标设备/通道始终映射到同一个 ape_id，保证重复同步不会制造重复设备。
        UUID stableId = UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
        return "GB_" + stableId.toString().replace("-", "");
    }

    private boolean booleanValue(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            if (value.isNumber()) {
                return value.asInt() != 0;
            }
            if (value.isTextual() && StringUtils.isNotBlank(value.asText())) {
                return isOnlineStatus(value.asText());
            }
        }
        return false;
    }

    private boolean isOnlineStatus(String status) {
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return "ON".equals(normalized) || "ONLINE".equals(normalized)
                || "OK".equals(normalized) || "TRUE".equals(normalized)
                || "1".equals(normalized);
    }

    private String text(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull()) {
                String result = value.asText();
                if (StringUtils.isNotBlank(result)) {
                    return result.trim();
                }
            }
        }
        return null;
    }

    private Integer integer(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isInt() || value.isLong()) {
                return value.asInt();
            }
            if (value.isTextual() && value.asText().matches("-?\\d+")) {
                return Integer.valueOf(value.asText());
            }
        }
        return null;
    }

    private String firstNotBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private Integer firstNotNull(Integer... values) {
        for (Integer value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static class PageData {
        private final List<JsonNode> items;
        private final int pages;

        private PageData(List<JsonNode> items, int pages) {
            this.items = items;
            this.pages = pages;
        }
    }

    private static class SyncSnapshot {
        private final List<HDevice> channels = new ArrayList<>();
        private int deviceCount;
        private int onlineChannelCount;
        private int skippedChannelCount;
    }
}
