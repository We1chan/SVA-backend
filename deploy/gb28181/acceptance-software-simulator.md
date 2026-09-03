# GB28181 软件模拟器验收

本文档用于项目没有实体 IPC 时的完整验收。验收对象是软件模拟的 GB28181 设备，不应在报告中表述为“实体摄像头”或“实机 IPC”。

## 1. 准备模拟器

在 Ubuntu 22.04/WSL 中执行：

```bash
sudo apt update
sudo apt install -y \
  build-essential curl git meson ninja-build python3 \
  gstreamer1.0-tools gstreamer1.0-plugins-base \
  gstreamer1.0-plugins-good gstreamer1.0-plugins-bad \
  gstreamer1.0-plugins-ugly gstreamer1.0-libav \
  libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev libglib2.0-dev

cd /path/to/easySVA
git clone https://github.com/sb-im/sbgb28181.git
git -C sbgb28181 checkout 1da9bc62134d4cb1fd4374f733583fb5997c3f0a
```

如果已存在 `sbgb28181` 目录，只需确认 `git -C sbgb28181 rev-parse HEAD` 输出上述提交。
将 `/path/to/easySVA` 替换为五个仓库所在的实际父目录。

## 2. 启动并验证注册

先启动 easySVA、WVP 和专用 GB ZLMediaKit，再在 SVA-backend 根目录执行：

```bash
bash deploy/gb28181/scripts/health.sh
bash deploy/gb28181/scripts/simulator-start.sh
```

如果 Windows 的 TCP 动态排除端口范围占用了默认后端端口 `9114`，可先用
`netsh interface ipv4 show excludedportrange protocol=tcp` 确认，再临时将后端运行在
`9214`。此时前端开发服务可在 PowerShell 中执行：

```powershell
$env:VUE_APP_BACKEND_URL="http://127.0.0.1:9214"
$env:port="8081"
npm run dev
```

这只是本机验收端口绕行，仓库默认端口仍为 `9114`。

默认模拟设备为：

| 配置 | 默认值 |
| --- | --- |
| 设备 ID | `44010200491320000001` |
| 通道 ID | `44010200491320000002` |
| SIP 平台 ID | `44010200492000000001` |
| SIP 域 | `4401020049` |
| SIP 密码 | `admin123` |
| 视频源 | GStreamer `videotestsrc` |
| 信令/媒体传输 | UDP / UDP |

可用 `GB28181_HOST_IP`、`GB28181_DEVICE_ID`、`GB28181_CHANNEL_ID`、`GB28181_SIP_PASSWORD`、`GB28181_SIMULATOR_SOURCE` 和 `SBGB28181_DIR` 覆盖默认值。

在 Windows PowerShell 执行：

```powershell
powershell -ExecutionPolicy Bypass -File deploy\gb28181\scripts\device-check.ps1
```

如果 `wsl -l -q` 显示的发行版名称不是 `Ubuntu-22.04`，追加参数
`-WslDistro <实际名称>`。

通过标准：输出中设备 `44010200491320000001` 为在线，且存在通道 `44010200491320000002`。等待最多 15 秒后，easySVA 设备页应出现类型 `GB28181`、名称 `ch1`、状态“在线”的记录。

## 3. 验证点播和页面预览

1. 在 easySVA 的“设备管理”页找到 `ch1`。
2. 点击“启动监控”，成功后状态变为“运行中”。
3. 点击“预览视频”，弹窗应持续显示 640x480、25 fps 的 GStreamer 测试画面。

命令行交叉验证：

```bash
ffprobe -v error -rtsp_transport tcp \
  -show_entries stream=codec_name,width,height,r_frame_rate \
  -of default=noprint_wrappers=1 \
  rtsp://127.0.0.1:9997/rtp/44010200491320000001_44010200491320000002
```

预期包含 `codec_name=h264`、`width=640`、`height=480`、`r_frame_rate=25/1`。

## 4. 验证离线与恢复

1. 点击“停止监控”，确认播放地址被清理。
2. 在模拟器终端按 `Ctrl+C`。WVP 默认按 60 秒心跳、3 次超时判断离线，因此最长等待约 180 秒。
3. 确认 WVP 和 easySVA 设备页都显示离线，且离线状态下不能成功启动点播。
4. 重新执行 `simulator-start.sh`，确认设备恢复上线并可再次预览。

## 5. 原 RTSP 链路回归

模拟器在线时另开终端执行：

```bash
bash deploy/gb28181/scripts/regression.sh
```

通过标准：Java 单元测试全部通过，WVP/专用 ZLM/easySVA 服务健康，原有 `DIRECT`/RTSP 回归流仍能被 `ffprobe` 读取。GB28181 链路使用独立的 9996/9997/9998 端口，不应影响原有 9992/9994 媒体服务。

## 6. 验收记录

本地已验证的基线：

- SIP REGISTER 经 401 Digest 认证后成功；WVP 可见 1 台在线模拟设备和 1 个在线通道。
- easySVA 自动同步为 `GB28181` 设备，并同步在线状态。
- 后端启动点播成功；ZLMediaKit 生成 `rtp/44010200491320000001_44010200491320000002` 媒体流。
- `ffprobe` 识别为 H.264 640x480 25 fps；前端弹窗持续播放 GStreamer 测试画面。
- 停止监控后播放地址和 ZLM 媒体流被清理；模拟器离线后 easySVA 显示“离线/停用”，且拒绝启动点播。
- 重新运行模拟器后设备恢复在线并再次点播成功。
- 原 DIRECT/RTSP 回归流仍可读取为 H.264 640x854 15 fps。
