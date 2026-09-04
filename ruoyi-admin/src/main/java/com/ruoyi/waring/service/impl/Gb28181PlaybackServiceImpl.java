package com.ruoyi.waring.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.waring.domain.Gb28181PlaybackInfo;
import com.ruoyi.waring.service.Gb28181PlaybackService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * 通过 WVP HTTP API 管理 GB28181 实时点播。
 *
 * <p>模块：流媒体协议组 / GB28181 取流适配。输入为国标设备与通道编号，
 * 输出为 WVP/ZLMediaKit 建立会话后生成的播放地址；本类不直接发送 SIP 信令。</p>
 */
@Service
public class Gb28181PlaybackServiceImpl implements Gb28181PlaybackService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${gb28181.enabled:false}")
    private boolean enabled;

    @Value("${gb28181.wvp-base-url:http://127.0.0.1:18080}")
    private String wvpBaseUrl;

    @Value("${gb28181.play-timeout-ms:20000}")
    private int playTimeoutMs;

    private final RestTemplate restTemplate = new RestTemplate();

    @PostConstruct
    private void configureHttpClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3000);
        requestFactory.setReadTimeout(playTimeoutMs);
        restTemplate.setRequestFactory(requestFactory);
    }

    @Override
    public Gb28181PlaybackInfo start(String deviceId, String channelId) {
        validateIds(deviceId, channelId);
        JsonNode data = request(buildUri("start", deviceId, channelId), "开始国标点播");

        String streamId = text(data, "stream");
        // 浏览器优先使用低延迟 WebSocket-FLV；不可用时按播放器兼容性依次降级。
        String playUrl = firstNotBlank(text(data, "ws_flv"), text(data, "flv"),
                text(data, "ws_fmp4"), text(data, "fmp4"), text(data, "hls"));
        String rtspUrl = text(data, "rtsp");
        if (StringUtils.isBlank(streamId) || StringUtils.isBlank(playUrl) || StringUtils.isBlank(rtspUrl)) {
            cleanupIncompletePlayback(deviceId, channelId);
            throw new ServiceException("WVP 点播成功但返回的播放地址不完整");
        }

        Gb28181PlaybackInfo playbackInfo = new Gb28181PlaybackInfo();
        playbackInfo.setStreamId(streamId);
        playbackInfo.setMediaServerId(text(data, "mediaServerId"));
        playbackInfo.setPlayUrl(playUrl);
        playbackInfo.setRtspUrl(rtspUrl);
        return playbackInfo;
    }

    @Override
    public void stop(String deviceId, String channelId) {
        validateIds(deviceId, channelId);
        request(buildUri("stop", deviceId, channelId), "停止国标点播");
    }

    private JsonNode request(URI uri, String action) {
        JsonNode response;
        try {
            response = restTemplate.getForObject(uri, JsonNode.class);
        } catch (HttpStatusCodeException ex) {
            String message = extractErrorMessage(ex.getResponseBodyAsString());
            throw new ServiceException(action + "失败: " + message);
        } catch (RestClientException ex) {
            throw new ServiceException(action + "失败: " + ex.getMessage());
        }

        if (response == null || response.isNull()) {
            throw new ServiceException(action + "失败: WVP 返回空响应");
        }
        return unwrap(response, action);
    }

    private JsonNode unwrap(JsonNode response, String action) {
        JsonNode current = response;
        // WVP 不同版本可能返回一层或多层 {code, data} 包装，限制三层避免异常响应无限下钻。
        for (int level = 0; level < 3; level++) {
            if (!current.has("code")) {
                if (level == 0) {
                    throw new ServiceException(action + "失败: WVP 响应格式无效");
                }
                return current;
            }

            int code = current.path("code").asInt(-1);
            if (code != 0 && code != 200) {
                throw new ServiceException(action + "失败: " + current.path("msg").asText("unknown"));
            }

            JsonNode data = current.get("data");
            if (data == null || data.isNull()) {
                return current;
            }
            current = data;
        }
        return current;
    }

    private void cleanupIncompletePlayback(String deviceId, String channelId) {
        try {
            request(buildUri("stop", deviceId, channelId), "回收不完整国标点播");
        } catch (ServiceException ignored) {
            // 原始 start 响应缺失播放地址才是调用方需要处理的主错误；清理失败不覆盖它。
        }
    }

    private String extractErrorMessage(String body) {
        if (StringUtils.isBlank(body)) {
            return "WVP HTTP 请求异常";
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            String message = text(root, "msg", "message", "error");
            return StringUtils.isBlank(message) ? "WVP HTTP 请求异常" : message;
        } catch (Exception ignored) {
            return "WVP HTTP 请求异常";
        }
    }

    private URI buildUri(String action, String deviceId, String channelId) {
        return UriComponentsBuilder.fromUriString(normalizedBaseUrl())
                .pathSegment("api", "play", action, deviceId, channelId)
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

    private void validateIds(String deviceId, String channelId) {
        if (!enabled) {
            throw new ServiceException("GB28181 点播未启用");
        }
        if (StringUtils.isBlank(deviceId) || StringUtils.isBlank(channelId)) {
            throw new ServiceException("GB28181 设备编号和通道编号不能为空");
        }
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

    private String firstNotBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }
}
