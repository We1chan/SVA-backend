package com.ruoyi.waring.mapper;

import com.ruoyi.waring.domain.HDevice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 统一设备表的数据访问边界。
 *
 * <p>共享模块：通用 CRUD 服务所有流源；GB28181 专用写操作必须限定协议类型，
 * 防止快照同步或会话清理影响 DIRECT/PLATFORM 设备。</p>
 *
 * <p>两条 GB28181 集成路线共享该边界：目录同步路线以 {@code gb28181_channel}
 * 为权威目录，向 h_device 写入 device_type/play_url 等镜像列（selectGbDevice /
 * upsertGbDevice / markGbDevicesOffline / updateGbDeviceOnlineByChannel）；
 * WVP 点播路线以 h_device 上的 gb_* 列为准（upsertGb28181Device /
 * updateGb28181Playback / clearGb28181Playback / markAllGb28181DevicesOffline /
 * clearOfflineGb28181Playback）。</p>
 */
@Mapper
@Repository
public interface HDeviceMapper {

    void insertDevice(HDevice device);

    void deleteDevice();

    HDevice selectDeviceByApeId(String apeId);

    // ---- 目录同步路线（以 gb28181_channel 为权威目录，写 device_type 等镜像列）----

    HDevice selectGbDevice(@Param("zlmServerId") Long zlmServerId,
                           @Param("gbDeviceId") String gbDeviceId,
                           @Param("gbChannelId") String gbChannelId);

    int upsertGbDevice(HDevice device);

    int markGbDevicesOffline(@Param("zlmServerId") Long zlmServerId);

    int updateGbDeviceOnlineByChannel(@Param("zlmServerId") Long zlmServerId,
                                      @Param("gbDeviceId") String gbDeviceId,
                                      @Param("gbChannelId") String gbChannelId,
                                      @Param("isOnline") String isOnline);

    // ---- WVP 点播路线（GB28181 播放桥）----

    /** 将全部 GB28181 通道预置为离线，随后由本轮 WVP 快照恢复实际状态。 */
    int markAllGb28181DevicesOffline();

    /** 清理离线国标通道已失效的点播地址与会话标识。 */
    int clearOfflineGb28181Playback();

    /** 按稳定 ape_id 插入或更新一个 WVP 通道，不影响其他流源类型。 */
    int upsertGb28181Device(HDevice device);

    /** 保存一次 WVP 点播产生的浏览器地址、RTSP 地址及媒体标识。 */
    int updateGb28181Playback(@Param("apeId") String apeId,
                              @Param("playUrl") String playUrl,
                              @Param("streamId") String streamId,
                              @Param("streamUrl") String streamUrl,
                              @Param("mediaServerId") String mediaServerId);

    /** 点播停止或失败时清除会话字段，设备目录信息继续保留。 */
    int clearGb28181Playback(@Param("apeId") String apeId);

    // ---- 通用 CRUD / 监控状态 ----

    int insertDeviceCrud(HDevice device);

    int updateDevice(HDevice device);

    int deleteDeviceByApeIds(@Param("apeIds") String[] apeIds);

    int updateMonitorStateByApeId(@Param("apeId") String apeId,
                                  @Param("monitorStatus") String monitorStatus);

    int updatePlayUrlByApeId(@Param("apeId") String apeId,
                             @Param("playUrl") String playUrl);

    int updateZlmProxyKeyByApeId(@Param("apeId") String apeId,
                                 @Param("zlmProxyKey") String zlmProxyKey);

    List<HDevice> selectDeviceList(HDevice device);

    List<HDevice> selectLDeviceList(HDevice device);

    int getDeviceNum();

    int getDeviceNumByOrg(HDevice device);

    int getDeviceEnableNum();

    int getDeviceEnableNumByOrg(HDevice device);

}
