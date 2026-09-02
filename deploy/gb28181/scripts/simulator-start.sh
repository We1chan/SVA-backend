#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
workspace_root="$(dirname -- "$backend_root")"
simulator_root="${SBGB28181_DIR:-$workspace_root/sbgb28181}"
plugin_root="$simulator_root/gst-gb28181sink"
plugin_build="$plugin_root/build"

server_ip="${GB28181_HOST_IP:-}"
if [[ -z "$server_ip" ]]; then
  server_ip="$(ip -4 route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([^ ]*\).*/\1/p' | head -n 1)"
fi
if [[ -z "$server_ip" ]]; then
  server_ip="$(hostname -I | awk '{print $1}')"
fi

wvp_base_url="${GB28181_WVP_BASE_URL:-http://127.0.0.1:18080}"
server_id="${GB28181_SERVER_ID:-44010200492000000001}"
domain="${GB28181_DOMAIN:-4401020049}"
device_id="${GB28181_DEVICE_ID:-44010200491320000001}"
channel_id="${GB28181_CHANNEL_ID:-44010200491320000002}"
password="${GB28181_SIP_PASSWORD:-admin123}"
source_uri="${GB28181_SIMULATOR_SOURCE:-test}"
expected_revision="1da9bc62134d4cb1fd4374f733583fb5997c3f0a"

for command_name in curl gst-inspect-1.0 meson ninja python3; do
  command -v "$command_name" >/dev/null 2>&1 || {
    printf 'Missing dependency: %s\n' "$command_name" >&2
    exit 1
  }
done

[[ -f "$simulator_root/gb28181_pusher.py" ]] || {
  printf 'Simulator not found: %s\n' "$simulator_root" >&2
  printf 'Clone https://github.com/sb-im/sbgb28181 at revision %s first.\n' "$expected_revision" >&2
  exit 1
}

curl --noproxy '*' -fsS "$wvp_base_url/api/device/query/devices?page=1&count=1" >/dev/null || {
  printf 'WVP is not reachable at %s\n' "$wvp_base_url" >&2
  exit 1
}

if [[ ! -f "$plugin_build/build.ninja" ]]; then
  meson setup "$plugin_build" "$plugin_root"
fi
meson compile -C "$plugin_build"

export GST_PLUGIN_PATH="$plugin_build${GST_PLUGIN_PATH:+:$GST_PLUGIN_PATH}"
export GST_REGISTRY="${TMPDIR:-/tmp}/easysva-gb28181-gst-registry-$$.bin"
gst-inspect-1.0 gb28181sink >/dev/null
gst-inspect-1.0 x264enc >/dev/null
gst-inspect-1.0 mpegpsmux >/dev/null

current_revision="$(git -C "$simulator_root" rev-parse HEAD 2>/dev/null || true)"
if [[ -n "$current_revision" && "$current_revision" != "$expected_revision" ]]; then
  printf 'Warning: simulator revision is %s; acceptance used %s.\n' \
    "$current_revision" "$expected_revision" >&2
fi

configure_udp_media() {
  local response
  for _ in $(seq 1 45); do
    response="$(curl --noproxy '*' -fsS -X POST \
      "$wvp_base_url/api/device/query/transport/$device_id/UDP" 2>/dev/null || true)"
    if grep -Eq '"code"[[:space:]]*:[[:space:]]*0' <<<"$response"; then
      printf 'WVP media transport set to UDP for %s.\n' "$device_id"
      return 0
    fi
    sleep 1
  done
  printf 'Warning: could not set WVP media transport to UDP automatically.\n' >&2
  return 0
}

configure_udp_media &
transport_pid=$!
cleanup() {
  kill "$transport_pid" 2>/dev/null || true
  rm -f -- "$GST_REGISTRY"
}
trap cleanup EXIT INT TERM

printf 'Starting software GB28181 device %s / channel %s at %s.\n' \
  "$device_id" "$channel_id" "$server_ip"
printf 'Press Ctrl+C to stop it and exercise the offline-state acceptance case.\n'

python3 "$simulator_root/gb28181_pusher.py" \
  --server-ip "$server_ip" \
  --server-port 5060 \
  --server-id "$server_id" \
  --domain "$domain" \
  --agent-id "$device_id" \
  --agent-password "$password" \
  --channel-id "$channel_id" \
  --source "$source_uri" \
  --udp \
  --local-ip "$server_ip" \
  --verbose
