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
 */
@Mapper
@Repository
public interface HDeviceMapper {

    void insertDevice(HDevice device);

    void deleteDevice();

    HDevice selectDeviceByApeId(String apeId);

    int insertDeviceCrud(HDevice device);

    int updateDevice(HDevice device);

    int deleteDeviceByApeIds(@Param("apeIds") String[] apeIds);

    int updateMonitorStateByApeId(@Param("apeId") String apeId,
                                  @Param("monitorStatus") String monitorStatus);

    int updatePlayUrlByApeId(@Param("apeId") String apeId,
                             @Param("playUrl") String playUrl);

    int updateZlmProxyKeyByApeId(@Param("apeId") String apeId,
                                 @Param("zlmProxyKey") String zlmProxyKey);

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

    List<HDevice> selectDeviceList(HDevice device);

    List<HDevice> selectLDeviceList(HDevice device);

    int getDeviceNum();

    int getDeviceNumByOrg(HDevice device);

    int getDeviceEnableNum();

    int getDeviceEnableNumByOrg(HDevice device);


}
