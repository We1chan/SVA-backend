package com.ruoyi.waring.service;

import java.util.Map;

/**
 * WVP 设备目录到 easySVA 设备模型的同步边界。
 *
 * <p>模块：流媒体协议组 / 设备同步与在线状态。同步结果按通道落库；
 * 点播启停不属于本服务职责。</p>
 */
public interface Gb28181SyncService {

    /**
     * 拉取一份完整的 WVP 设备/通道快照并更新本地设备表。
     *
     * @return 本轮同步数量、在线数量、跳过数量与耗时
     */
    Map<String, Object> syncDevices();
}
