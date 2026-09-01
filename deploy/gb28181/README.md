# easySVA GB28181 本地运行模块

本模块只负责流媒体组的本地基础设施，不修改 easySVA 原有 RTSP、分析器或前端链路。

## 服务边界

- WVP-PRO：GB28181 SIP 注册、心跳、目录查询和 INVITE。
- ZLMediaKit：接收设备发送的 PS-RTP，并转换为 RTSP/HTTP 等播放协议。
- easySVA：后续通过 WVP 接口同步设备、通道及在线状态。

## 本机端口

| 服务 | 端口 |
| --- | --- |
| SIP TCP/UDP | 5060 |
| WVP HTTP API | 18080 |
| GB ZLM HTTP API | 9996 |
| GB ZLM RTSP | 9997 |
| GB ZLM RTMP | 9998 |
| GB ZLM RTP 单端口 | 10000 |
| GB ZLM RTP 多端口 | 40002-45000 |

这些端口与检查点一使用的 8080、9992、9994 分离，两个链路可以同时运行。

## 启动与检查

1. 确保同级目录存在 `wvp-GB28181-pro` 和 `SVA-mediaServer`。
2. 使用 Java 21 编译 WVP；easySVA 后端继续固定使用 Java 17。
3. 安装 `systemd/easysva-gb-media.service` 和 `systemd/easysva-wvp.service`。
4. 启动后运行：

   ```bash
   bash deploy/gb28181/scripts/health.sh
   ```

5. WLAN 为公用网络时，以管理员身份运行：

   ```powershell
   powershell -ExecutionPolicy Bypass -File deploy\gb28181\scripts\open-firewall.ps1
   ```

当前配置面向本机开发环境。SIP 密码、数据库密码和 ZLM API secret 在真实部署前必须改为环境专用值。
