package com.ruoyi.waring.service;

import com.ruoyi.waring.domain.HDevice;

import java.util.List;
import java.util.Map;

/**
 * 设备管理业务边界。
 *
 * <p>共享模块：调用方使用统一接口管理不同流源；实现层负责保持 DIRECT 原流程
 * 与 GB28181 点播流程相互隔离。</p>
 */
public interface HDeviceService {
    void insertDevice(HDevice device);

    void deleteDevice();

    HDevice selectDeviceByApeId(String apeId);

    int insertDeviceCrud(HDevice device);

    int updateDevice(HDevice device);

    int deleteDeviceByApeIds(String[] apeIds);

    List<HDevice> selectDeviceList(HDevice device, Long userId);

    Map<String, Object> getDeviceNum(Long userId);

    Map<String, Object> getDirectLiveUrl(String apeId);

    List<HDevice> selectLDeviceList(HDevice device, Long userId);

    /** 按设备流源类型启动 DIRECT 代理或 GB28181 点播。 */
    int startMonitor(String apeId);

    /** 停止对应协议会话并清理临时播放状态。 */
    int stopMonitor(String apeId);

    /** 返回浏览器当前可用的预览地址；该方法本身不隐式启动点播。 */
    Map<String, Object> previewMonitor(String apeId);
}
