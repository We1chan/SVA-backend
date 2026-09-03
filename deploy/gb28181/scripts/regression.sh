#!/usr/bin/env bash
# 模块：流媒体协议组 / 自动回归。
# 串联静态检查、幂等迁移、后端测试、服务健康与原 RTSP 兼容性验证。
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
health_script="$backend_root/deploy/gb28181/scripts/health.sh"
rtsp_url="${RTSP_REGRESSION_URL:-rtsp://127.0.0.1:9994/live/checkpoint1}"
java_home="${EASYSVA_JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
mysql_defaults_file="${EASYSVA_MYSQL_DEFAULTS_FILE:-}"

mysql_command=(mysql)
if [[ -n "$mysql_defaults_file" ]]; then
  if [[ ! -r "$mysql_defaults_file" ]]; then
    printf 'MySQL defaults file is not readable: %s\n' "$mysql_defaults_file" >&2
    exit 1
  fi
  mysql_command+=("--defaults-extra-file=$mysql_defaults_file")
fi
mysql_command+=(--protocol=socket -uroot easySVA)

business_migration="${EASYSVA_GB28181_BUSINESS_MIGRATION:-}"
if [[ -z "$business_migration" ]]; then
  for candidate in \
    "$backend_root/../FWWsva/deploy/sql/20260901_gb28181_business.sql" \
    "/opt/FWWsva/deploy/sql/20260901_gb28181_business.sql"; do
    if [[ -f "$candidate" ]]; then
      business_migration="$candidate"
      break
    fi
  done
fi
if [[ ! -f "$business_migration" ]]; then
  printf 'GB28181 business migration not found. Set EASYSVA_GB28181_BUSINESS_MIGRATION.\n' >&2
  exit 1
fi

printf '1/6 shell syntax\n'
bash -n "$backend_root/deploy/gb28181/scripts/health.sh"
bash -n "$backend_root/deploy/gb28181/scripts/wvp-start.sh"
bash -n "$backend_root/deploy/gb28181/scripts/simulator-start.sh"

printf '2/6 systemd unit verification\n'
systemd-analyze verify \
  "$backend_root/deploy/gb28181/systemd/easysva-gb-media.service" \
  "$backend_root/deploy/gb28181/systemd/easysva-wvp.service"

printf '3/6 repeatable database migrations\n'
for migration in 001_extend_h_device.sql 002_add_gb_stream_url.sql; do
  "${mysql_command[@]}" < "$backend_root/deploy/gb28181/sql/$migration"
  "${mysql_command[@]}" < "$backend_root/deploy/gb28181/sql/$migration"
done
"${mysql_command[@]}" < "$business_migration"
"${mysql_command[@]}" < "$business_migration"

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
