package com.ruoyi.web.service.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.DeploymentTask;
import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.waring.domain.SvaServer;
import com.ruoyi.waring.domain.ZlmServer;
import com.ruoyi.waring.mapper.HDeviceMapper;
import com.ruoyi.waring.mapper.SvaServerMapper;
import com.ruoyi.waring.mapper.ZlmServerMapper;
import com.ruoyi.waring.service.HAlgorithmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.ruoyi.system.domain.DeploymentTaskAlgorithm;

/**
 * easySVA 后端与分析器之间的任务编排客户端。
 *
 * <p>共享模块：DIRECT 设备使用原 ZLMediaKit 路径；GB28181 设备仅在在线且已
 * 点播时传递 WVP/ZLMediaKit 生成的 RTSP 地址。</p>
 */
@Service
public class DeploymentAnalyzerClient
{
    private static final Logger log = LoggerFactory.getLogger(DeploymentAnalyzerClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long DEFAULT_SERVER_ID = 1L;
    private static final String ENGINE_A_SERVER = "A-SERVER";
    private static final String ENGINE_M_SERVER = "M-SERVER";
    private static final String DEFAULT_ZLM_APP = "live";
    private static final String DEFAULT_SVA_APP = "analyzer";
    private static final int DEFAULT_ALARM_INTERVAL_SEC = 180;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private HAlgorithmService hAlgorithmService;

    @Autowired
    private HDeviceMapper hDeviceMapper;

    @Autowired
    private ZlmServerMapper zlmServerMapper;

    @Autowired
    private SvaServerMapper svaServerMapper;

    public AnalyzerResult addControl(DeploymentTask task, String recognitionRegion)
    {
        if (task == null)
        {
            return AnalyzerResult.fail("布控任务不存在");
        }

        BindingConfig bindingConfig = resolveBinding(task.getDeviceId());
        if (bindingConfig == null)
        {
            return AnalyzerResult.fail("未绑定可用服务器或配置缺失");
        }

        String apeId = task.getDeviceId();
        String streamUrl = buildStreamUrl(bindingConfig, apeId);
        if (StringUtils.isEmpty(streamUrl))
        {
            return AnalyzerResult.fail("设备无可播放媒体流（GB28181设备离线或媒体未同步）",
                "buildStreamUrl返回空: deviceId=" + apeId);
        }

        boolean pushStream = Boolean.TRUE.equals(task.getPushEnabled());
        boolean frontendOverlayEnabled = Boolean.TRUE.equals(task.getFrontendOverlayEnabled());
        String pushStreamUrl = buildPushStreamUrl(bindingConfig, task.getDeploymentId());
        String algorithmStreamUrl = buildAlgorithmStreamUrl(bindingConfig, task.getDeploymentId());
        if (pushStream && StringUtils.isEmpty(pushStreamUrl))
        {
            return AnalyzerResult.fail("pushStreamUrl不能为空");
        }

        String analyzerAddUrl = bindingConfig.getAnalyzerBaseUrl() + "/api/control/add";
        if (log.isDebugEnabled())
        {
            log.debug("布控启动URL, deploymentId={}, deviceId={}, streamUrl={}, pushStreamUrl={}, algorithmStreamUrl={}, analyzerUrl={}",
                task.getDeploymentId(), apeId, maskSensitiveUrl(streamUrl),
                pushStream ? maskSensitiveUrl(pushStreamUrl) : "", maskSensitiveUrl(algorithmStreamUrl),
                maskSensitiveUrl(analyzerAddUrl));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", task.getDeploymentId());
        payload.put("streamCode", apeId);
        payload.put("streamApp", bindingConfig.zlmApp);
        payload.put("streamName", apeId);
        payload.put("streamUrl", streamUrl);
        payload.put("pushStream", pushStream);
        if (pushStream)
        {
            payload.put("pushStreamUrl", pushStreamUrl);
        }
        String renderMode = pushStream ? "server_overlay" : (frontendOverlayEnabled ? "ws_overlay" : "detect_only");
        payload.put("renderMode", renderMode);
        payload.put("serverOverlayEnabled", pushStream);
        payload.put("wsOverlayEnabled", !pushStream && frontendOverlayEnabled);
        payload.put("saveImageEnabled", true);
        payload.put("saveVideoEnabled", !ENGINE_M_SERVER.equals(normalizeRecordEngine(task.getRecordEngine())));

        List<Map<String, Object>> algorithmTasks = buildAlgorithmTasks(task);
        if (algorithmTasks.isEmpty())
        {
            return AnalyzerResult.fail("algorithmTasks不能为空");
        }

        DeploymentTaskAlgorithm primaryTask = resolvePrimaryTask(task);
        if (primaryTask == null)
        {
            return AnalyzerResult.fail("algorithmTasks不能为空");
        }

        payload.put("algorithmCode", StringUtils.nvl(primaryTask.getAlgorithmCode(), ""));
        payload.put("objectCodes", primaryTask.getTargetCodes());
        payload.put("recognitionRegion", recognitionRegion);
        appendGeometryPayload(payload, task);
        Integer minInterval = task.getAlarmIntervalSec();
        payload.put("minInterval", minInterval == null || minInterval <= 0 ? DEFAULT_ALARM_INTERVAL_SEC : minInterval);
        payload.put("dwellEnabled", Boolean.TRUE.equals(task.getDwellEnabled()));
        Long dwellThresholdMs = task.getDwellThresholdMs();
        payload.put("dwellThresholdMs", dwellThresholdMs == null ? 5000L : dwellThresholdMs);

        String objectStr = StringUtils.nvl(hAlgorithmService.getObjectStrByCode(primaryTask.getAlgorithmCode()), "");
        String apiUrl = StringUtils.nvl(hAlgorithmService.getApiUrlByCode(primaryTask.getAlgorithmCode()), "");
        payload.put("object_str", objectStr);
        payload.put("api_url", apiUrl);
        payload.put("algorithmTasks", algorithmTasks);

        return postJson(analyzerAddUrl, payload, "add");
    }

    /**
     * Idempotently ensure that a persisted RUNNING deployment exists in the
     * analyzer. The analyzer treats an already-running control as success, so
     * this method is safe for startup recovery and live-preview requests.
     */
    public AnalyzerResult ensureControl(DeploymentTask task)
    {
        String recognitionRegion = buildRecognitionRegion(task);
        if (StringUtils.isEmpty(recognitionRegion))
        {
            return AnalyzerResult.fail("geometryConfig中至少需要一个3点以上的主区域");
        }
        return addControl(task, recognitionRegion);
    }

    private String buildRecognitionRegion(DeploymentTask task)
    {
        if (task == null || StringUtils.isBlank(task.getGeometryConfig()))
        {
            return null;
        }
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(task.getGeometryConfig());
            JsonNode regions = root == null ? null : root.get("regions");
            if (regions == null || !regions.isArray())
            {
                return null;
            }

            String fallback = null;
            for (JsonNode region : regions)
            {
                String value = buildRecognitionRegionFromPoints(region == null ? null : region.get("points"));
                if (StringUtils.isEmpty(value) && region != null)
                {
                    value = buildRecognitionRegionFromPoints(region.get("polygon"));
                }
                if (StringUtils.isEmpty(value) && region != null)
                {
                    value = buildRecognitionRegionFromPoints(region.get("vertices"));
                }
                if (StringUtils.isEmpty(value))
                {
                    continue;
                }
                if (fallback == null)
                {
                    fallback = value;
                }
                if (region.path("primary").asBoolean(false) || region.path("isPrimary").asBoolean(false))
                {
                    return value;
                }
            }
            return fallback;
        }
        catch (Exception ex)
        {
            log.warn("恢复布控时解析geometryConfig失败，deploymentId={}", task.getDeploymentId(), ex);
            return null;
        }
    }

    private String buildRecognitionRegionFromPoints(JsonNode points)
    {
        if (points == null || !points.isArray())
        {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        int count = 0;
        if (points.size() > 0 && points.get(0).isNumber())
        {
            for (int index = 0; index + 1 < points.size(); index += 2)
            {
                count = appendRecognitionPoint(builder, count, points.get(index), points.get(index + 1));
            }
        }
        else
        {
            for (JsonNode point : points)
            {
                if (point == null)
                {
                    continue;
                }
                JsonNode x = point.isArray() && point.size() >= 2 ? point.get(0) : point.get("x");
                JsonNode y = point.isArray() && point.size() >= 2 ? point.get(1) : point.get("y");
                count = appendRecognitionPoint(builder, count, x, y);
            }
        }
        return count >= 3 ? builder.toString() : null;
    }

    private int appendRecognitionPoint(StringBuilder builder, int count, JsonNode x, JsonNode y)
    {
        if (x == null || y == null || !x.isNumber() || !y.isNumber())
        {
            return count;
        }
        if (builder.length() > 0)
        {
            builder.append(',');
        }
        builder.append(x.asDouble()).append(',').append(y.asDouble());
        return count + 1;
    }

    private void appendGeometryPayload(Map<String, Object> payload, DeploymentTask task)
    {
        if (payload == null || task == null || StringUtils.isBlank(task.getGeometryConfig()))
        {
            return;
        }
        try
        {
            JsonNode geometryNode = OBJECT_MAPPER.readTree(task.getGeometryConfig());
            if (!geometryNode.isObject())
            {
                return;
            }
            payload.put("geometryConfig", OBJECT_MAPPER.convertValue(geometryNode, Object.class));
            JsonNode regionsNode = geometryNode.get("regions");
            if (regionsNode != null && regionsNode.isArray())
            {
                payload.put("regions", OBJECT_MAPPER.convertValue(regionsNode, Object.class));
            }
            JsonNode linesNode = geometryNode.get("lines");
            if (linesNode != null && linesNode.isArray())
            {
                payload.put("lines", OBJECT_MAPPER.convertValue(linesNode, Object.class));
            }
        }
        catch (Exception ex)
        {
            log.warn("解析geometryConfig失败，deploymentId={}", task.getDeploymentId(), ex);
        }
    }

    private DeploymentTaskAlgorithm resolvePrimaryTask(DeploymentTask task)
    {
        if (task == null)
        {
            return null;
        }
        if (task.getAlgorithmTasks() != null)
        {
            for (DeploymentTaskAlgorithm item : task.getAlgorithmTasks())
            {
                if (item != null && StringUtils.isNotBlank(item.getAlgorithmCode()) && item.getTargetCodes() != null && !item.getTargetCodes().isEmpty())
                {
                    return item;
                }
            }
        }
        return null;
    }

    private List<Map<String, Object>> buildAlgorithmTasks(DeploymentTask task)
    {
        List<Map<String, Object>> items = new ArrayList<>();
        if (task == null)
        {
            return items;
        }

        List<DeploymentTaskAlgorithm> sourceTasks = task.getAlgorithmTasks();
        if (sourceTasks == null || sourceTasks.isEmpty())
        {
            return items;
        }

        for (DeploymentTaskAlgorithm item : sourceTasks)
        {
            List<String> targetCodes = item == null ? Collections.<String>emptyList() : item.getTargetCodes();
            if (item == null || StringUtils.isBlank(item.getAlgorithmCode()) || targetCodes == null || targetCodes.isEmpty())
            {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("algorithmCode", item.getAlgorithmCode());
            if (item.getDetectFps() != null)
            {
                row.put("detectFps", item.getDetectFps());
            }
            if (item.getScoreThreshold() != null)
            {
                row.put("scoreThreshold", item.getScoreThreshold());
            }
            if (item.getNmsThreshold() != null)
            {
                row.put("nmsThreshold", item.getNmsThreshold());
            }
            row.put("objectCodes", targetCodes);
            row.put("object_str", StringUtils.nvl(hAlgorithmService.getObjectStrByCode(item.getAlgorithmCode()), ""));
            row.put("api_url", StringUtils.nvl(hAlgorithmService.getApiUrlByCode(item.getAlgorithmCode()), ""));
            items.add(row);
        }
        return items;
    }

    public AnalyzerResult cancelControl(DeploymentTask task)
    {
        if (task == null)
        {
            return AnalyzerResult.fail("布控任务不存在");
        }
        if (StringUtils.isEmpty(task.getDeploymentId()))
        {
            return AnalyzerResult.fail("deploymentId不能为空");
        }

        BindingConfig bindingConfig = resolveBinding(task.getDeviceId());
        if (bindingConfig == null)
        {
            return AnalyzerResult.fail("未绑定可用服务器或配置缺失");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", task.getDeploymentId());
        return postJson(bindingConfig.getAnalyzerBaseUrl() + "/api/control/cancel", payload, "cancel");
    }

    public AnalyzerResult updateLiveOutput(DeploymentTask task, boolean videoEnabled,
                                           boolean liveEventEnabled, float wsEventFps)
    {
        if (task == null || StringUtils.isEmpty(task.getDeploymentId()))
        {
            return AnalyzerResult.fail("布控任务不存在");
        }
        BindingConfig bindingConfig = resolveBinding(task.getDeviceId());
        if (bindingConfig == null)
        {
            return AnalyzerResult.fail("未绑定可用服务器或配置缺失");
        }

        // Server-overlay tasks already publish their algorithm stream from the
        // original add request. Re-adding is idempotent and also restores the
        // in-memory control after an analyzer/WSL restart. Older analyzers do
        // not expose /api/control/live-output, so avoid calling that endpoint.
        if (videoEnabled && Boolean.TRUE.equals(task.getPushEnabled())
            && StringUtils.isNotBlank(task.getAlgorithmStreamUrl()))
        {
            AnalyzerResult ensured = ensureControl(task);
            if (!ensured.isSuccess())
            {
                return ensured;
            }
            return AnalyzerResult.ok("算法流已启用", ensured.getDetailMessage());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("controlCode", task.getDeploymentId());
        payload.put("videoEnabled", videoEnabled);
        payload.put("liveEventEnabled", liveEventEnabled);
        payload.put("wsEventFps", wsEventFps);
        if (videoEnabled)
        {
            String pushStreamUrl = buildPushStreamUrl(bindingConfig, task.getDeploymentId());
            if (StringUtils.isEmpty(pushStreamUrl))
            {
                return AnalyzerResult.fail("无法生成算法流推送地址");
            }
            payload.put("pushStreamUrl", pushStreamUrl);
        }
        return postJson(bindingConfig.getAnalyzerBaseUrl() + "/api/control/live-output",
            payload, "live-output");
    }

    public AnalyzerResult cancelControl(String deploymentId)
    {
        return AnalyzerResult.fail("缺少deviceId，无法定位绑定的SVA服务器");
    }

    public String buildStreamUrl(String apeId)
    {
        BindingConfig bindingConfig = resolveBinding(apeId);
        if (bindingConfig == null)
        {
            return null;
        }
        return buildStreamUrl(bindingConfig, apeId);
    }

    public String buildPushStreamUrl(String apeId, String deploymentId)
    {
        BindingConfig bindingConfig = resolveBinding(apeId);
        if (bindingConfig == null)
        {
            return null;
        }
        return buildPushStreamUrl(bindingConfig, deploymentId);
    }

    public String buildAlgorithmStreamUrl(String apeId, String deploymentId)
    {
        BindingConfig bindingConfig = resolveBinding(apeId);
        if (bindingConfig == null)
        {
            return null;
        }
        return buildAlgorithmStreamUrl(bindingConfig, deploymentId);
    }

    private AnalyzerResult postJson(String url, Map<String, Object> payload, String action)
    {
        try
        {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            String body = response.getBody();

            if (!response.getStatusCode().is2xxSuccessful())
            {
                String detail = "HTTP状态码=" + response.getStatusCode().value()
                    + ", 响应=" + StringUtils.nvl(body, "");
                return AnalyzerResult.fail("调用失败，HTTP状态码=" + response.getStatusCode().value(), detail);
            }

            if (StringUtils.isEmpty(body))
            {
                return AnalyzerResult.fail("调用失败，响应为空", "响应为空");
            }

            JsonNode root = OBJECT_MAPPER.readTree(body);
            int code = root.path("code").asInt(0);
            String msg = root.path("msg").asText("");
            String shortMessage = resolveShortMessage(action, code, msg);
            if (code == 1000)
            {
                return AnalyzerResult.ok(shortMessage, body);
            }
            // analyzer 重启后内存控制表会清空，此时取消一个已不存在的控制属于幂等成功：
            // 目标状态（该布控不再运行）已经成立，数据库应收敛为 STOPPED，而不是卡在 RUNNING。
            if (isIdempotentMissingControl(action, root))
            {
                return AnalyzerResult.ok(shortMessage, body);
            }
            return AnalyzerResult.fail(shortMessage, body);
        }
        catch (ResourceAccessException ex)
        {
            String detail = buildExceptionDetail(ex);
            if (isTimeoutException(ex))
            {
                log.error("调用analyzer接口超时, url={}", url, ex);
                return AnalyzerResult.fail("连接超时", detail);
            }
            log.error("调用analyzer接口失败, url={}", url, ex);
            return AnalyzerResult.fail("调用算法服务接口异常", detail);
        }
        catch (Exception ex)
        {
            log.error("调用analyzer接口失败, url={}", url, ex);
            return AnalyzerResult.fail("调用算法服务接口异常", buildExceptionDetail(ex));
        }
    }

    private boolean isTimeoutException(Throwable ex)
    {
        Throwable current = ex;
        while (current != null)
        {
            if (current instanceof SocketTimeoutException)
            {
                return true;
            }
            String className = current.getClass().getName();
            if (className != null && className.toLowerCase().contains("timeout"))
            {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String buildExceptionDetail(Exception ex)
    {
        String message = ex.getMessage();
        if (StringUtils.isEmpty(message))
        {
            return ex.getClass().getName();
        }
        return ex.getClass().getName() + ": " + message;
    }

    private String resolveShortMessage(String action, int code, String msg)
    {
        String normalizedAction = normalizeText(action);
        String normalizedMsg = normalizeText(msg);

        if ("add".equals(normalizedAction) && code == 1000)
        {
            if ("add success".equals(normalizedMsg))
            {
                return "创建成功，布控已启动";
            }
            if ("the control is running".equals(normalizedMsg))
            {
                return "该布控已在运行，无需重复创建";
            }
        }

        if ("add".equals(normalizedAction) && code == 0
            && "push stream connect error".equals(normalizedMsg))
        {
            return "推送失败，请稍后再试！";
        }

        if ("add".equals(normalizedAction)
            && normalizedMsg.contains("pull stream connect error"))
        {
            return "读取视频流失败，请确认设备启动了视频流";
        }

        if ("cancel".equals(normalizedAction))
        {
            if (code == 1000 && "control is running, cancel success".equals(normalizedMsg))
            {
                return "停止成功，布控已取消";
            }
            if (code == 0 && "there is no such control".equals(normalizedMsg))
            {
                return "停止成功，布控已取消";
            }
        }

        if (code == 1000)
        {
            return "操作成功";
        }
        return StringUtils.isEmpty(msg) ? "操作失败" : msg;
    }

    /**
     * 判定是否为「取消时控制已不存在」的幂等场景。
     * 仅对 cancel 生效，且要求 code 是显式数字 0 —— 缺失 / null / 非数字的 code 仍按失败处理，
     * 避免把畸形响应误判为成功。
     */
    private boolean isIdempotentMissingControl(String action, JsonNode root)
    {
        if (!"cancel".equals(normalizeText(action)))
        {
            return false;
        }
        JsonNode codeNode = root.path("code");
        if (!codeNode.isNumber())
        {
            return false;
        }
        return codeNode.asInt() == 0
            && "there is no such control".equals(normalizeText(root.path("msg").asText("")));
    }

    private String normalizeText(String text)
    {
        if (StringUtils.isBlank(text))
        {
            return "";
        }
        return text.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private String normalizeRecordEngine(String recordEngine)
    {
        if (StringUtils.isBlank(recordEngine))
        {
            return ENGINE_A_SERVER;
        }

        String normalized = recordEngine.trim();
        if (ENGINE_M_SERVER.equals(normalized))
        {
            return ENGINE_M_SERVER;
        }
        return ENGINE_A_SERVER;
    }

    private BindingConfig resolveBinding(String apeId)
    {
        if (StringUtils.isBlank(apeId))
        {
            return null;
        }

        HDevice device = hDeviceMapper.selectDeviceByApeId(apeId);
        if (device == null)
        {
            return null;
        }

        Long zlmServerId = device.getZlm_server_id() == null ? DEFAULT_SERVER_ID : device.getZlm_server_id();
        Long svaServerId = device.getSva_server_id() == null ? DEFAULT_SERVER_ID : device.getSva_server_id();

        ZlmServer zlmServer = zlmServerMapper.selectEnabledById(zlmServerId);
        SvaServer svaServer = svaServerMapper.selectEnabledById(svaServerId);
        if (zlmServer == null || svaServer == null)
        {
            return null;
        }

        if (StringUtils.isBlank(zlmServer.getHost())
            || zlmServer.getMedia_rtsp_port() == null
            || zlmServer.getMedia_http_port() == null)
        {
            return null;
        }

        if (StringUtils.isBlank(svaServer.getHost()) || svaServer.getAnalyzer_port() == null)
        {
            return null;
        }

        String zlmApp = StringUtils.isBlank(zlmServer.getApp()) ? DEFAULT_ZLM_APP : zlmServer.getApp().trim();
        String svaApp = StringUtils.isBlank(svaServer.getApp()) ? DEFAULT_SVA_APP : svaServer.getApp().trim();
        boolean gb28181 = "GB28181".equalsIgnoreCase(device.getStream_source_type());
        // 国标分析只能消费当前在线点播产生的 RTSP 地址；DIRECT 设备仍按原 ZLM 路径拼接。
        String sourceStreamUrl = gb28181 && "1".equals(device.getIs_online())
            ? device.getGb_stream_url() : null;
        // 目录同步路线（device_type='GB28181'）：play_url 由 GB28181 目录同步写入 ZLM 媒体引用，
        // 需显式解析 app/stream 拼接 analyzer 拉流地址，不能按 RTSP 惯例用 ape_id 作为流名
        // （ZLM 中不存在该流名，拉流必失败）。
        String catalogPlayUrl = null;
        String catalogPlay = device.getPlay_url();
        if (StringUtils.isNotBlank(catalogPlay)
            && "GB28181".equalsIgnoreCase(device.getDevice_type())
            && !gb28181)
        {
            String mediaRef = extractZlmMediaRef(catalogPlay);
            if (mediaRef == null)
            {
                log.warn("GB28181设备缺少可解析的play_url, 无法构建analyzer拉流地址, deviceId={}", apeId);
            }
            else
            {
                catalogPlayUrl = "rtsp://" + zlmServer.getHost().trim() + ":" + zlmServer.getMedia_rtsp_port()
                    + "/" + mediaRef;
            }
        }
        Integer rtmpPort = zlmServer.getMedia_rtmp_port();
        int effectiveRtmpPort = (rtmpPort != null && rtmpPort > 0) ? rtmpPort : 9995;
        return new BindingConfig(zlmServer.getHost().trim(), zlmApp, zlmServer.getMedia_rtsp_port(),
            effectiveRtmpPort, zlmServer.getMedia_http_port(), svaServer.getHost().trim(), svaApp,
            svaServer.getAnalyzer_port(), gb28181, sourceStreamUrl, catalogPlayUrl);
    }

    private String buildStreamUrl(BindingConfig config, String apeId)
    {
        if (StringUtils.isBlank(apeId))
        {
            return null;
        }
        // 目录同步路线：显式绑定媒体引用优先（已解析为 rtsp://host:rtspPort/app/stream）
        if (!StringUtils.isBlank(config.catalogPlayUrl))
        {
            return config.catalogPlayUrl;
        }
        if (config.gb28181)
        {
            return StringUtils.isBlank(config.sourceStreamUrl) ? null : config.sourceStreamUrl;
        }
        return "rtsp://" + config.zlmHost + ":" + config.zlmMediaRtspPort + "/" + config.zlmApp + "/" + apeId;
    }

    /**
     * 从 ZLM play_url（如 ws://host:port/live/stream.live.flv）解析出 "app/stream" 媒体引用。
     */
    private String extractZlmMediaRef(String playUrl)
    {
        if (StringUtils.isBlank(playUrl))
        {
            return null;
        }
        String rest = playUrl.trim();
        int scheme = rest.indexOf("://");
        if (scheme >= 0)
        {
            rest = rest.substring(scheme + 3);
        }
        int slash = rest.indexOf('/');
        if (slash < 0)
        {
            return null;
        }
        String path = rest.substring(slash + 1);
        while (path.endsWith("/"))
        {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith(".live.flv"))
        {
            path = path.substring(0, path.length() - ".live.flv".length());
        }
        else if (path.endsWith(".flv"))
        {
            path = path.substring(0, path.length() - ".flv".length());
        }
        if (path.isEmpty())
        {
            return null;
        }
        return path;
    }

    private String buildPushStreamUrl(BindingConfig config, String deploymentId)
    {
        if (StringUtils.isBlank(deploymentId))
        {
            return null;
        }
        return "rtmp://" + config.zlmHost + ":" + config.zlmMediaRtmpPort + "/" + config.svaApp + "/" + deploymentId;
    }

    private String buildAlgorithmStreamUrl(BindingConfig config, String deploymentId)
    {
        if (StringUtils.isBlank(deploymentId))
        {
            return null;
        }
        // 浏览器播放算法输出流（带画框）走 HTTP-FLV，flv.js 不支持 ws 协议。
        return "http://" + config.zlmHost + ":" + config.zlmMediaHttpPort + "/" + config.svaApp + "/"
            + deploymentId + ".live.flv";
    }

    private String maskSensitiveUrl(String url)
    {
        if (StringUtils.isBlank(url))
        {
            return url;
        }
        return url.replaceAll("(?i)([?&](secret|token|access_token|auth|sign|signature)=)[^&]*", "$1***");
    }

    private static class BindingConfig
    {
        private final String zlmHost;
        private final String zlmApp;
        private final int zlmMediaRtspPort;
        private final int zlmMediaRtmpPort;
        private final int zlmMediaHttpPort;
        private final String svaHost;
        private final String svaApp;
        private final int svaAnalyzerPort;
        private final boolean gb28181;
        private final String sourceStreamUrl;
        private final String catalogPlayUrl;

        private BindingConfig(String zlmHost, String zlmApp, int zlmMediaRtspPort, int zlmMediaRtmpPort,
            int zlmMediaHttpPort, String svaHost, String svaApp, int svaAnalyzerPort,
            boolean gb28181, String sourceStreamUrl, String catalogPlayUrl)
        {
            this.zlmHost = zlmHost;
            this.zlmApp = zlmApp;
            this.zlmMediaRtspPort = zlmMediaRtspPort;
            this.zlmMediaRtmpPort = zlmMediaRtmpPort;
            this.zlmMediaHttpPort = zlmMediaHttpPort;
            this.svaHost = svaHost;
            this.svaApp = svaApp;
            this.svaAnalyzerPort = svaAnalyzerPort;
            this.gb28181 = gb28181;
            this.sourceStreamUrl = sourceStreamUrl;
            this.catalogPlayUrl = catalogPlayUrl;
        }

        private String getAnalyzerBaseUrl()
        {
            return "http://" + svaHost + ":" + svaAnalyzerPort;
        }
    }

    public static class AnalyzerResult
    {
        private final boolean success;
        private final String message;
        private final String detailMessage;

        private AnalyzerResult(boolean success, String message, String detailMessage)
        {
            this.success = success;
            this.message = message;
            this.detailMessage = detailMessage;
        }

        public static AnalyzerResult ok(String message)
        {
            return new AnalyzerResult(true, message, message);
        }

        public static AnalyzerResult ok(String message, String detailMessage)
        {
            return new AnalyzerResult(true, message, detailMessage);
        }

        public static AnalyzerResult fail(String message)
        {
            return new AnalyzerResult(false, message, message);
        }

        public static AnalyzerResult fail(String message, String detailMessage)
        {
            return new AnalyzerResult(false, message, detailMessage);
        }

        public boolean isSuccess()
        {
            return success;
        }

        public String getMessage()
        {
            return message;
        }

        public String getDetailMessage()
        {
            return detailMessage;
        }
    }
}
