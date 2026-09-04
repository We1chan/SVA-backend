package com.ruoyi.waring.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.waring.domain.Gb28181Channel;
import com.ruoyi.waring.domain.Gb28181MediaStream;
import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.waring.domain.ZlmServer;
import com.ruoyi.waring.mapper.Gb28181CatalogMapper;
import com.ruoyi.waring.mapper.HDeviceMapper;
import com.ruoyi.waring.mapper.ZlmServerMapper;
import com.ruoyi.waring.service.Gb28181DeviceSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class Gb28181DeviceSyncServiceImpl implements Gb28181DeviceSyncService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String GB_DEVICE_TYPE = "GB28181";
    private static final String ONLINE = "1";
    private static final String OFFLINE = "2";
    private final Gb28181CatalogMapper catalogMapper;
    private final ZlmServerMapper zlmServerMapper;
    private final HDeviceMapper hDeviceMapper;
    private final RestTemplate restTemplate;

    public Gb28181DeviceSyncServiceImpl(Gb28181CatalogMapper catalogMapper) {
        this(catalogMapper, null, null, new RestTemplate());
    }

    @Autowired
    public Gb28181DeviceSyncServiceImpl(Gb28181CatalogMapper catalogMapper, ZlmServerMapper zlmServerMapper,
                                        HDeviceMapper hDeviceMapper, RestTemplate restTemplate) {
        this.catalogMapper = catalogMapper;
        this.zlmServerMapper = zlmServerMapper;
        this.hDeviceMapper = hDeviceMapper;
        this.restTemplate = restTemplate == null ? new RestTemplate() : restTemplate;
    }

    @Override
    public CatalogSyncResult syncCatalog(List<Gb28181Channel> channels) {
        CatalogSyncResult result = new CatalogSyncResult();
        for (Gb28181Channel channel : channels == null ? Collections.<Gb28181Channel>emptyList() : channels) {
            validateCatalogChannel(channel);
            catalogMapper.upsertCatalogChannel(channel);
            result.incrementSynchronizedChannels();
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceSyncResult syncDevices(Long zlmServerId, List<Gb28181Channel> channels) {
        if (zlmServerId == null) {
            throw new ServiceException("zlmServerId 不能为空");
        }
        if (zlmServerMapper == null || hDeviceMapper == null) {
            throw new ServiceException("国标设备同步缺少持久化依赖");
        }
        ZlmServer server = zlmServerMapper.selectEnabledById(zlmServerId);
        if (server == null) {
            throw new ServiceException("ZLM 节点不存在或未启用: " + zlmServerId);
        }

        if (channels == null) {
            // 未携带目录快照（如设备管理页“同步国标设备”按钮仅触发对账）：不新增、
            // 不更新、不剔除任何通道，保持现状；真正的目录下发必须携带权威快照。
            return new DeviceSyncResult();
        }

        List<Gb28181Channel> incoming = channels;
        for (Gb28181Channel channel : incoming) {
            validateCatalogChannel(channel);
            if (!zlmServerId.equals(channel.getZlmServerId())) {
                throw new ServiceException("目录通道 zlmServerId 与请求不一致: " + channel.getChannelId());
            }
        }

        List<Gb28181Channel> cataloged = catalogMapper.selectChannelsByZlmServerId(zlmServerId);
        Set<String> incomingKeys = incoming.stream().map(this::identityKey).collect(Collectors.toSet());
        DeviceSyncResult result = new DeviceSyncResult();
        for (Gb28181Channel existing : cataloged) {
            if (!incomingKeys.contains(identityKey(existing))) {
                catalogMapper.markCatalogChannelOffline(
                    zlmServerId, existing.getDeviceId(), existing.getChannelId());
                existing.setCatalogOnline(false);
                int rows = hDeviceMapper.updateGbDeviceOnlineByChannel(
                    zlmServerId, existing.getDeviceId(), existing.getChannelId(), OFFLINE);
                result.addOfflineMarked(rows);
            }
        }

        for (Gb28181Channel channel : incoming) {
            HDevice existed = hDeviceMapper.selectGbDevice(
                zlmServerId, channel.getDeviceId(), channel.getChannelId());
            if (isWvpOwnedMirror(existed)) {
                throw new ServiceException("目录通道已由 WVP 同步维护，拒绝旧目录覆盖: "
                    + channel.getChannelId());
            }
            catalogMapper.upsertCatalogChannel(channel);
            HDevice mirror = new HDevice();
            mirror.setApe_id(existed == null
                ? buildApeId(channel.getDeviceId(), channel.getChannelId())
                : existed.getApe_id());
            mirror.setName(channel.getName());
            mirror.setDevice_type(GB_DEVICE_TYPE);
            mirror.setStream_source_type("PLATFORM");
            mirror.setGb_platform_id(channel.getPlatformId());
            mirror.setGb_device_id(channel.getDeviceId());
            mirror.setGb_channel_id(channel.getChannelId());
            mirror.setPlay_url(channel.getPlayUrl());
            mirror.setZlm_server_id(zlmServerId);
            mirror.setIs_online(channel.isCatalogOnline() ? ONLINE : OFFLINE);
            mirror.setMonitor_status(existed == null ? "STOPPED" : existed.getMonitor_status());
            mirror.setSync_source("GB28181目录");
            hDeviceMapper.upsertGbDevice(mirror);
            if (existed == null) {
                result.incrementCreated();
            } else {
                result.incrementUpdated();
            }
        }
        return result;
    }

    @Override
    public MediaRefreshResult refreshMedia(Long zlmServerId, List<Gb28181MediaStream> streams) {
        if (zlmServerId == null) {
            throw new ServiceException("zlmServerId 不能为空");
        }
        Set<String> activeBindings = (streams == null ? Collections.<Gb28181MediaStream>emptyList() : streams).stream()
            .filter(this::hasBinding)
            .map(this::bindingKey)
            .collect(Collectors.toCollection(HashSet::new));

        MediaRefreshResult result = new MediaRefreshResult();
        for (Gb28181Channel channel : catalogMapper.selectChannelsByZlmServerId(zlmServerId)) {
            boolean available = activeBindings.contains(bindingKey(channel));
            catalogMapper.updateMediaAvailability(channel.getId(), available);
            if (available) {
                result.incrementAvailable();
            } else {
                result.incrementUnavailable();
            }
            if (hDeviceMapper != null) {
                String online = available && channel.isCatalogOnline() ? ONLINE : OFFLINE;
                hDeviceMapper.updateGbDeviceOnlineByChannel(
                    zlmServerId, channel.getDeviceId(), channel.getChannelId(),
                    online);
            }
        }
        return result;
    }

    @Override
    public MediaRefreshResult refreshMediaFromZlm(Long zlmServerId) {
        if (zlmServerId == null || zlmServerMapper == null) {
            throw new ServiceException("媒体刷新缺少可用 ZLM 节点");
        }
        ZlmServer server = zlmServerMapper.selectEnabledById(zlmServerId);
        if (server == null || StringUtils.isBlank(server.getHost()) || server.getApi_port() == null) {
            throw new ServiceException("媒体刷新缺少可用 ZLM 节点");
        }
        String url = UriComponentsBuilder.fromUriString("http://" + server.getHost() + ":" + server.getApi_port()
                + "/index/api/getMediaList")
            .queryParam("secret", server.getSecret())
            .build(true).toUriString();
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                throw new ServiceException("调用 ZLM 媒体列表失败");
            }
            return refreshMedia(zlmServerId, parseMediaStreams(response.getBody()));
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("调用 ZLM 媒体列表失败");
        }
    }

    private List<Gb28181MediaStream> parseMediaStreams(String responseBody) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(responseBody);
        if (root == null || root.path("code").asInt(-1) != 0 || !root.path("data").isArray()) {
            throw new ServiceException("ZLM 媒体列表响应数据格式无效");
        }
        return java.util.stream.StreamSupport.stream(root.path("data").spliterator(), false)
            .map(item -> new Gb28181MediaStream(text(item, "vhost"), text(item, "app"), text(item, "stream")))
            .filter(this::hasBinding)
            .collect(Collectors.toList());
    }

    private void validateCatalogChannel(Gb28181Channel channel) {
        if (channel == null || StringUtils.isBlank(channel.getPlatformId()) || StringUtils.isBlank(channel.getDeviceId())
            || StringUtils.isBlank(channel.getChannelId())) {
            throw new ServiceException("国标目录缺少平台、设备或通道标识");
        }
        if (channel.getZlmServerId() != null && !hasBinding(channel)) {
            throw new ServiceException("国标目录媒体绑定必须同时提供 vhost、app 和 stream");
        }
    }

    private boolean hasBinding(Gb28181MediaStream stream) {
        return stream != null && StringUtils.isNotBlank(stream.getVhost()) && StringUtils.isNotBlank(stream.getApp())
            && StringUtils.isNotBlank(stream.getStream());
    }

    private boolean hasBinding(Gb28181Channel channel) {
        return channel != null && StringUtils.isNotBlank(channel.getVhost()) && StringUtils.isNotBlank(channel.getApp())
            && StringUtils.isNotBlank(channel.getStream());
    }

    private String bindingKey(Gb28181MediaStream stream) {
        return stream.getVhost() + "\u0000" + stream.getApp() + "\u0000" + stream.getStream();
    }

    private String bindingKey(Gb28181Channel channel) {
        return channel.getVhost() + "\u0000" + channel.getApp() + "\u0000" + channel.getStream();
    }

    private String identityKey(Gb28181Channel channel) {
        return channel.getDeviceId() + "\u0000" + channel.getChannelId();
    }

    private boolean isWvpOwnedMirror(HDevice device) {
        return device != null && GB_DEVICE_TYPE.equalsIgnoreCase(device.getStream_source_type());
    }

    private String buildApeId(String deviceId, String channelId) {
        String stream = "gb-" + String.valueOf(deviceId) + "-" + String.valueOf(channelId);
        return stream.replaceAll("[^A-Za-z0-9_-]", "");
    }

    private String text(JsonNode item, String field) {
        JsonNode value = item.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
