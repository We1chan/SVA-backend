package com.ruoyi.waring.domain;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.*;

/**
 * easySVA 统一设备模型。
 *
 * <p>共享模块：同时承载 DIRECT、PLATFORM 与 GB28181 设备；新增协议字段必须
 * 保持可空，避免破坏原有 RTSP 设备数据。</p>
 */
@Data
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class HDevice extends BaseEntity {
    @Excel(name = "设备编码")
    private String ape_id;
    @Excel(name = "设备名称")
    private String name;
    private String stream_source_type;
    private String direct_source_url;
    private String play_url;
    private String zlm_proxy_key;

    /** 设备类型镜像列（GB28181/RTSP 等），由 GB28181 目录同步写入，供告警 JOIN 与布控分流使用。 */
    private String device_type;
    /** GB28181 平台/目录标识（目录同步镜像）。 */
    private String gb_platform_id;
    /** 数据来源（如 GB28181 目录）。 */
    private String sync_source;

    // 流媒体协议组：WVP 目录标识、当前点播会话以及最近一次同步时间。
    private String gb_device_id;
    private String gb_channel_id;
    private String gb_media_server_id;
    private String gb_stream_id;
    private String gb_stream_url;
    private String gb_last_sync_time;
    private String resource_type;
    private String sub_type;
    @Excel(name = "IP地址")
    private String ip_addr;
    @Excel(name = "端口号")
    private Integer port;
    private String org_index;
    private String org_name;
    private String place_code;
    private String place;
    private String is_online;
    private String producer;
    private String producer_name;
    private String parent_code;
    private Long zlm_server_id;
    private Long sva_server_id;
    @Excel(name = "监控状态")
    private String monitor_status;
    private String create_time;
    private String update_time;
}
