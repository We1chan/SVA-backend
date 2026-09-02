package com.ruoyi.waring.service;

import com.ruoyi.waring.domain.Gb28181Channel;
import com.ruoyi.waring.domain.HDevice;

import java.util.List;
import java.util.Map;

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

    int startMonitor(String apeId);

    int stopMonitor(String apeId);

    Map<String, Object> previewMonitor(String apeId);

    /**
     * Reconcile the authoritative SIP/GB catalog snapshot for a ZLM node.
     * A {@code null} list means no snapshot and performs no reconciliation;
     * an explicit empty list is authoritative and reconciles existing GB
     * channels offline.
     */
    Map<String, Object> syncGb28181Catalog(Long zlmServerId, List<Gb28181Channel> channels);

    Map<String, Object> refreshGb28181Status(Long zlmServerId);
}
