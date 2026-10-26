#!/bin/sh
set -eu

url="${ALERT_WEBHOOK_URL:-}"
bearer_file="${ALERT_WEBHOOK_BEARER_FILE:-}"
config=/tmp/alertmanager.yml

case "$url" in
  "")
    ;;
  http://*|https://*)
    leftover=$(printf '%s' "$url" | tr -d 'A-Za-z0-9:/._~%+=?&-')
    if [ -n "$leftover" ]; then
      echo "ALERT_WEBHOOK_URL contains unsupported characters" >&2
      exit 1
    fi
    ;;
  *)
    echo "ALERT_WEBHOOK_URL must be empty or an http(s) URL" >&2
    exit 1
    ;;
esac

auth=""
if [ -n "$bearer_file" ]; then
  if [ ! -f "$bearer_file" ]; then
    echo "ALERT_WEBHOOK_BEARER_FILE does not exist" >&2
    exit 1
  fi
  bearer=$(tr -d '\r\n' < "$bearer_file")
  leftover=$(printf '%s' "$bearer" | tr -d 'A-Za-z0-9._~+/=-')
  if [ -z "$bearer" ] || [ -n "$leftover" ]; then
    echo "Alert webhook bearer token has an unsupported character" >&2
    exit 1
  fi
  auth="
    http_config:
      authorization:
        type: Bearer
        credentials: \"${bearer}\""
fi

if [ -n "$url" ]; then
  receiver="
  webhook_configs:
    - url: \"${url}\"
      send_resolved: true${auth}"
else
  receiver=""
fi

cat > "$config" <<EOF
route:
  receiver: default
  group_by: ["alertname", "severity"]
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 2h
receivers:
  - name: default${receiver}
EOF

exec /bin/alertmanager \
  --config.file="$config" \
  --storage.path=/alertmanager \
  --web.listen-address=0.0.0.0:9093
