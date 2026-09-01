#!/usr/bin/env bash
set -u

fail=0

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

check_udp() {
  local name="$1"
  local port="$2"
  if ss -lnu | grep -q ":${port} "; then
    printf '%-18s OK   UDP %s\n' "$name" "$port"
  else
    printf '%-18s FAIL UDP %s\n' "$name" "$port"
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

check_http "WVP HTTP" "http://127.0.0.1:18080/api/device/query/devices?page=1&count=1"
check_tcp "SIP TCP" 5060
check_udp "SIP UDP" 5060
check_http "GB ZLM HTTP" "http://127.0.0.1:9996/index/api/getApiList?secret=easySVA.GB28181.ZLM"
check_tcp "GB ZLM RTSP" 9997
check_http "Original Web" "http://127.0.0.1:8080/"
check_tcp "Original RTSP" 9994

exit "$fail"
