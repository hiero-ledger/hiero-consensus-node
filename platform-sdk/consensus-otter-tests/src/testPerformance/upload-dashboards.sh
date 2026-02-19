#!/bin/bash
# Upload all Grafana dashboard JSON files from the dashboards/ directory.
# Usage: upload-dashboards.sh [--grafana-url URL] [--datasource-uid UID] [--dashboards-dir DIR]
#
# Defaults:
#   --grafana-url    http://admin:admin@localhost:3000
#   --datasource-uid victoriametrics
#   --dashboards-dir <directory containing this script>/dashboards

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

GRAFANA_URL="http://admin:admin@localhost:3000"
DATASOURCE_UID="victoriametrics"
DASHBOARDS_DIR="$SCRIPT_DIR/dashboards"

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --grafana-url)    GRAFANA_URL="$2";    shift 2 ;;
    --datasource-uid) DATASOURCE_UID="$2"; shift 2 ;;
    --dashboards-dir) DASHBOARDS_DIR="$2"; shift 2 ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
done

if [ ! -d "$DASHBOARDS_DIR" ]; then
  echo "⚠️  Dashboards directory not found: $DASHBOARDS_DIR"
  exit 1
fi

echo "Uploading dashboards from $DASHBOARDS_DIR to $GRAFANA_URL..."
dashboard_count=0
failed_count=0

for dashboard_file in "$DASHBOARDS_DIR"/*.json; do
  [ -f "$dashboard_file" ] || continue
  echo "  Uploading $(basename "$dashboard_file")..."

  # Substitute DATASOURCE_UID_PLACEHOLDER with the actual UID
  dashboard_json=$(sed "s/DATASOURCE_UID_PLACEHOLDER/$DATASOURCE_UID/g" "$dashboard_file")

  response=$(curl -s -w "\n%{http_code}" -X POST "$GRAFANA_URL/api/dashboards/db" \
    -H "Content-Type: application/json" \
    -d "$dashboard_json")
  result="${response##*$'\n'}"
  body="${response%$'\n'*}"
  if [ "$result" = "200" ]; then
    echo "    ✓ Uploaded"
    ((dashboard_count++))
  else
    echo "    ⚠️  Upload failed (HTTP $result): $body"
    ((failed_count++))
  fi
done

echo "Done: $dashboard_count uploaded, $failed_count failed."
[ "$failed_count" -eq 0 ] || exit 1
