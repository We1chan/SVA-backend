#!/usr/bin/env bash
# 模块：流媒体协议组 / 本地运行检查。
# 检查 GB28181 独立服务，并同时确认原 RTSP 链路未被端口改造破坏。
set -u

fail=0
zlm_secret="${GB28181_ZLM_SECRET:-easySVA.GB28181.ZLM}"

check_tcp() {
  local name="$1"
  local port="$2"
  if ss -lnt | grep -q ":${port} "; then
    printf '%-18s OK   TCP %s\n' "$name" "$port"
  else
    printf '%-18s FAIL TCP %s\n' "$name" "$port"
    fail=1
  fi
}

check_sip_transports() {
  local port="$1"
  local tcp=0
  local udp=0
  ss -lnt | grep -q ":${port} " && tcp=1
  ss -lnu | grep -q ":${port} " && udp=1

  if (( tcp == 1 )); then
    printf '%-18s OK   TCP %s\n' "SIP TCP" "$port"
  else
    printf '%-18s WARN TCP %s not listening\n' "SIP TCP" "$port"
  fi
  if (( udp == 1 )); then
    printf '%-18s OK   UDP %s\n' "SIP UDP" "$port"
  else
    printf '%-18s WARN UDP %s not listening\n' "SIP UDP" "$port"
  fi
  if (( tcp == 0 && udp == 0 )); then
    printf '%-18s FAIL no SIP transport is listening on %s\n' "SIP" "$port"
    fail=1
  fi
}

check_http() {
  local name="$1"
  local url="$2"
  if curl -fsS -o /dev/null "$url"; then
    printf '%-18s OK   %s\n' "$name" "$url"
  else
    printf '%-18s FAIL %s\n' "$name" "$url"
    fail=1
  fi
}

check_zlm_api() {
  local name="$1"
  local endpoint="$2"
  local response
  response="$(curl --noproxy '*' -fsS --get \
    --data-urlencode "secret=$zlm_secret" "$endpoint" 2>/dev/null || true)"
  if grep -Eq '"code"[[:space:]]*:[[:space:]]*0' <<<"$response"; then
    printf '%-18s OK   %s\n' "$name" "$endpoint"
  else
    printf '%-18s FAIL %s (API secret or service error)\n' "$name" "$endpoint"
    fail=1
  fi
}

check_http "WVP HTTP" "http://127.0.0.1:18080/api/device/query/devices?page=1&count=1"
check_sip_transports 5060
check_zlm_api "GB ZLM HTTP" "http://127.0.0.1:9996/index/api/getApiList"
check_tcp "GB ZLM RTSP" 9997
check_http "Original Web" "http://127.0.0.1:8080/"
check_tcp "Original RTSP" 9994

exit "$fail"
