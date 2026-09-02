#!/usr/bin/env bash
# 模块：流媒体协议组 / 自动回归。
# 串联静态检查、幂等迁移、后端测试、服务健康与原 RTSP 兼容性验证。
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
health_script="$backend_root/deploy/gb28181/scripts/health.sh"
rtsp_url="${RTSP_REGRESSION_URL:-rtsp://127.0.0.1:9994/live/checkpoint1}"
java_home="${EASYSVA_JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

printf '1/6 shell syntax\n'
bash -n "$backend_root/deploy/gb28181/scripts/health.sh"
bash -n "$backend_root/deploy/gb28181/scripts/wvp-start.sh"

printf '2/6 systemd unit verification\n'
systemd-analyze verify \
  "$backend_root/deploy/gb28181/systemd/easysva-gb-media.service" \
  "$backend_root/deploy/gb28181/systemd/easysva-wvp.service"

printf '3/6 repeatable database migrations\n'
for migration in 001_extend_h_device.sql 002_add_gb_stream_url.sql; do
  mysql --protocol=socket -uroot easySVA < "$backend_root/deploy/gb28181/sql/$migration"
  mysql --protocol=socket -uroot easySVA < "$backend_root/deploy/gb28181/sql/$migration"
done

printf '4/6 backend unit and regression tests\n'
(
  cd "$backend_root"
  export JAVA_HOME="$java_home"
  export PATH="$JAVA_HOME/bin:$PATH"
  mvn -pl ruoyi-admin -am test
)

printf '5/6 live service health\n'
bash "$health_script"
curl --noproxy '*' -fsS -o /dev/null \
  'http://127.0.0.1:18080/api/device/query/devices?page=1&count=1'

printf '6/6 original RTSP regression\n'
timeout 20 ffprobe -v error -rtsp_transport tcp -select_streams v:0 \
  -show_entries stream=codec_name,width,height -of default=nw=1 "$rtsp_url"

printf 'GB28181 platform and original RTSP regression passed.\n'
