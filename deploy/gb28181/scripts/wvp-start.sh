#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
workspace_root="$(dirname "$backend_root")"
jar_file="$(find "$workspace_root/wvp-GB28181-pro/target" -maxdepth 1 -type f -name 'wvp-pro-*.jar' ! -name '*.original' -printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d' ' -f2-)"

if [[ -z "$jar_file" || ! -f "$jar_file" ]]; then
  echo "WVP jar not found under $workspace_root/wvp-GB28181-pro/target." >&2
  exit 1
fi

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

exec /usr/lib/jvm/java-21-openjdk-amd64/bin/java -Xms256m -Xmx768m \
  -jar "$jar_file" \
  --spring.config.location="file:$backend_root/deploy/gb28181/config/wvp.local.yml"
