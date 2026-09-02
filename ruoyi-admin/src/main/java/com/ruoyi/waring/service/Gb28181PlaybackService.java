package com.ruoyi.waring.service;

import com.ruoyi.waring.domain.Gb28181PlaybackInfo;

/**
 * GB28181 点播会话边界。
 *
 * <p>模块：流媒体协议组 / GB28181 取流适配。实现层通过 WVP 发起或停止
 * INVITE；设备状态同步由 {@link Gb28181SyncService} 独立负责。</p>
 */
public interface Gb28181PlaybackService {

    /**
     * 启动指定国标通道的实时点播，并返回前端与分析器可消费的地址。
     */
    Gb28181PlaybackInfo start(String deviceId, String channelId);

    /**
     * 停止指定国标通道的点播会话。
     */
    void stop(String deviceId, String channelId);
}
