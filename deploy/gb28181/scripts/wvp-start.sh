#!/usr/bin/env bash
# 模块：流媒体协议组 / WVP 本地启动器。
# 自动定位构建产物与本机可达地址，再以隔离配置启动 SIP/API 服务。
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
workspace_root="$(dirname "$backend_root")"
jar_file="$(find "$workspace_root/wvp-GB28181-pro/target" -maxdepth 1 -type f -name 'wvp-pro-*.jar' ! -name '*.original' -printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d' ' -f2-)"
java_bin="${EASYSVA_JAVA21_BIN:-/usr/lib/jvm/java-21-openjdk-amd64/bin/java}"

if [[ -z "$jar_file" || ! -f "$jar_file" ]]; then
  echo "WVP jar not found under $workspace_root/wvp-GB28181-pro/target." >&2
  exit 1
fi
if [[ ! -x "$java_bin" ]]; then
  echo "Java 21 is not executable: $java_bin" >&2
  echo "Set EASYSVA_JAVA21_BIN when Java 21 is installed at another path." >&2
  exit 1
fi

# SIP/SDP 必须公布设备可达的地址，不能固定为仅主机可用的 127.0.0.1。
host_ip="$(ip route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([^ ]*\).*/\1/p' | head -n 1)"
if [[ -z "$host_ip" ]]; then
  host_ip="$(hostname -I | awk '{print $1}')"
fi
if [[ -z "$host_ip" ]]; then
  echo "Unable to determine the LAN IPv4 address for GB28181." >&2
  exit 1
fi

export GB28181_HOST_IP="$host_ip"
export WVP_DB_USERNAME="${WVP_DB_USERNAME:-wvp}"
export WVP_DB_PASSWORD="${WVP_DB_PASSWORD:-easySVA.GB28181}"
export GB28181_SIP_PASSWORD="${GB28181_SIP_PASSWORD:-admin123}"
export GB28181_ZLM_SECRET="${GB28181_ZLM_SECRET:-easySVA.GB28181.ZLM}"
export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:mysql://127.0.0.1:3307/wvp?useUnicode=true&characterEncoding=UTF8&rewriteBatchedStatements=true&serverTimezone=Asia/Shanghai&useSSL=false&allowMultiQueries=true&allowPublicKeyRetrieval=true}"

exec "$java_bin" -Xms256m -Xmx768m \
  -jar "$jar_file" \
  --spring.config.location="file:$backend_root/deploy/gb28181/config/wvp.local.yml"
