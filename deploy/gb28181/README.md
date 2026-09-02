# easySVA GB28181 本地运行模块

本模块只负责流媒体组的本地基础设施，不修改 easySVA 原有 RTSP、分析器或前端链路。

跨仓库的数据流、代码入口和维护边界见
[流媒体协议组代码地图](https://github.com/We1chan/FWWsva/blob/master/docs/gb28181-code-map.md)。

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

## 自动回归

平台侧代码、数据库迁移、运行服务和原有 RTSP 链路可以用一条命令重复验证：

```bash
bash deploy/gb28181/scripts/regression.sh
```

脚本会依次检查 shell/systemd 配置、重复执行数据库迁移、运行 Java 单元测试、检查 WVP/ZLM/easySVA 服务，并用 `ffprobe` 验证原有 RTSP 流。如果本机使用其他回归流，可通过 `RTSP_REGRESSION_URL` 覆盖默认地址。WVP 点播超时固定为 18 秒，easySVA 后端读取超时为 20 秒，均低于现有浏览器请求的 23 秒，避免页面先超时而后台仍继续点播。

## 软件模拟器接入与验收

没有实体相机时，可以使用 `sbgb28181` 在 Ubuntu 22.04/WSL 中模拟一台完整的 GB28181 设备。它会完成 SIP REGISTER/401 Digest、心跳、目录上报、INVITE/BYE，并把 GStreamer 测试画面封装为 PS/H.264 RTP 推给 WVP/ZLMediaKit。

1. 按 [`acceptance-software-simulator.md`](acceptance-software-simulator.md) 安装依赖并锁定已验证的模拟器版本。
2. 运行 `bash deploy/gb28181/scripts/simulator-start.sh`。脚本只在模拟器目录本地编译 GStreamer 插件，不做全局安装；设备注册后会自动将 WVP 媒体传输设为 UDP。
3. 运行 `device-check.ps1`，再在 easySVA 设备页点击“启动监控”和“预览视频”。
4. `Ctrl+C` 停止模拟器可验证离线同步；重新运行脚本可验证恢复上线。

模拟器的环境变量、预期结果、API 与 `ffprobe` 复验方法见软件模拟验收文档。

## 实体 IPC 接入与验收

本仓库已完成平台侧的 WVP、ZLMediaKit 和 easySVA 同步逻辑。如果学校要求额外使用硬件复验，可再将一台支持 GB28181 的 IPC 与本机放在同一局域网，按下列参数接入。

1. 先运行 `bash deploy/gb28181/scripts/health.sh`。其中 `WVP HTTP`、`SIP TCP`、`SIP UDP`、`GB ZLM HTTP` 和 `GB ZLM RTSP` 必须均为 `OK`。
2. 以管理员身份运行 `open-firewall.ps1`，允许局域网访问 SIP、WebSocket 预览、RTSP 和 RTP 端口：

   ```powershell
   powershell -ExecutionPolicy Bypass -File deploy\gb28181\scripts\open-firewall.ps1
   ```

3. 在 IPC 的 GB28181 配置页填写本机 `health.sh` 输出的局域网 IP，并使用以下 WVP 本地开发配置：

   | IPC 配置项 | 值 |
   | --- | --- |
   | SIP 服务器地址 | 本机局域网 IP |
   | SIP 服务器端口 | `5060` |
   | SIP 域 | `4401020049` |
   | SIP 平台 ID | `44010200492000000001` |
   | 注册密码 | `admin123` |
   | 传输协议 | 先使用 UDP；设备或网络要求时可改 TCP |

4. 保存 IPC 配置并等待注册/目录上报，运行下面的只读检查脚本：

   ```powershell
   powershell -ExecutionPolicy Bypass -File deploy\gb28181\scripts\device-check.ps1
   ```

   脚本应显示至少一个设备。随后等待最多 15 秒，easySVA 的设备页面会出现类型为 `GB28181` 的通道；也可以由管理员调用 `POST /waring/device/gb28181/sync` 立即同步。
5. 在设备页面启动监控并打开预览。平台会经 WVP 发起点播、由专用 GB ZLMediaKit 输出预览地址；停止监控后相应播放地址会被清理。原有 `DIRECT`/RTSP 设备不走该分支。

如果第 4 步持续显示 `0` 台设备，应优先核对 IPC 是否和电脑处于同一子网、SIP IP/端口/平台 ID/密码是否完全匹配，以及 Windows 防火墙规则是否已成功添加。

实体 IPC 验收完成时，应保存以下证据：设备注册与在线状态、通道目录、启动点播后的 WVP 返回值、ZLM 中的 `rtp` 流、easySVA 设备页预览、设备断电后的离线状态，以及一台原有 DIRECT/RTSP 设备的预览结果。没有实体 IPC 或符合 GB28181 的软件模拟器时，只能确认平台侧和 RTSP 回归通过，不能把 SIP 注册与 PS-RTP 链路标记为已验收。
