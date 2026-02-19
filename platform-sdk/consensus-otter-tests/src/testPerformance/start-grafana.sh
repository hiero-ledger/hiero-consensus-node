#!/bin/bash
# Start Grafana + VictoriaMetrics and import metrics data

set -e

NETWORK_NAME="metrics-network"
VM_CONTAINER="victoriametrics"
GRAFANA_CONTAINER="grafana"
KEEP_DATA=false
SHUTDOWN=false

# Parse arguments
METRICS_ARGS=()
for arg in "$@"; do
  if [ "$arg" = "--keep-data" ]; then
    KEEP_DATA=true
  elif [ "$arg" = "--shutdown" ]; then
    SHUTDOWN=true
  else
    METRICS_ARGS+=("$arg")
  fi
done

# Handle shutdown mode
if [ "$SHUTDOWN" = true ]; then
  echo "Shutting down metrics visualization stack..."
  docker rm -f $VM_CONTAINER $GRAFANA_CONTAINER 2>/dev/null || true
  docker volume rm victoria-metrics-data 2>/dev/null || true
  docker network rm $NETWORK_NAME 2>/dev/null || true
  echo "Done."
  exit 0
fi

echo "Starting metrics visualization stack..."

if [ "$KEEP_DATA" = true ]; then
  echo "Keeping existing data (--keep-data flag detected)"
  # Only remove containers, keep volumes and network
  docker rm -f $VM_CONTAINER $GRAFANA_CONTAINER 2>/dev/null || true
  # Create network if it doesn't exist
  docker network inspect $NETWORK_NAME >/dev/null 2>&1 || docker network create $NETWORK_NAME
else
  # Clean up any existing resources
  echo "Cleaning up existing containers, volumes, and network..."
  docker rm -f $VM_CONTAINER $GRAFANA_CONTAINER 2>/dev/null || true
  docker volume rm victoria-metrics-data 2>/dev/null || true
  docker network rm $NETWORK_NAME 2>/dev/null || true

  # Create fresh Docker network
  echo "Creating Docker network..."
  docker network create $NETWORK_NAME
fi

# Start VictoriaMetrics
echo "Starting VictoriaMetrics..."
docker run -d \
  --name $VM_CONTAINER \
  --network $NETWORK_NAME \
  -p 8428:8428 \
  -v victoria-metrics-data:/victoria-metrics-data \
  victoriametrics/victoria-metrics:latest

# Wait for VictoriaMetrics to be ready
echo "Waiting for VictoriaMetrics to start..."
sleep 3

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$SCRIPT_DIR/import-metrics.sh" "${METRICS_ARGS[@]}"

# Start Grafana
echo "Starting Grafana..."
docker run -d \
  --name $GRAFANA_CONTAINER \
  --network $NETWORK_NAME \
  -p 3000:3000 \
  -e GF_SECURITY_ADMIN_PASSWORD=admin \
  -e GF_AUTH_ANONYMOUS_ENABLED=true \
  -e GF_AUTH_ANONYMOUS_ORG_ROLE=Admin \
  -e GF_AUTH_DISABLE_LOGIN_FORM=true \
  grafana/grafana:latest

# Wait for Grafana to be ready
echo "Waiting for Grafana to start..."
sleep 5

# Configure VictoriaMetrics as a data source
echo "Configuring VictoriaMetrics data source..."
DATASOURCE_UID=$(curl -s -X POST http://admin:admin@localhost:3000/api/datasources \
  -H "Content-Type: application/json" \
  -d '{
    "name": "VictoriaMetrics",
    "type": "prometheus",
    "uid": "victoriametrics",
    "url": "http://victoriametrics:8428",
    "access": "proxy",
    "isDefault": true
  }' | grep -o '"uid":"[^"]*"' | cut -d'"' -f4)

if [ -z "$DATASOURCE_UID" ]; then
  DATASOURCE_UID="victoriametrics"
  echo "⚠️  Data source might already exist, using default UID"
fi

# Upload all dashboards from the dashboards/ directory
"$SCRIPT_DIR/upload-dashboards.sh" --datasource-uid "$DATASOURCE_UID"

echo ""
echo "✓ Stack started successfully!"
echo ""
echo "Access points:"
echo "  Grafana Dashboard: http://localhost:3000/d/consensus-metrics (admin/admin)"
echo "  VictoriaMetrics:   http://localhost:8428"
echo ""
echo "To import more metrics:"
echo "  ./import-metrics.sh [paths...]"
echo ""
echo "To stop and delete all data:"
echo "  ./start-grafana.sh --shutdown"
echo ""
echo "Usage:"
echo "  ./start-grafana.sh [--keep-data] [--shutdown] [paths...]"
echo "  --keep-data: Keep existing data volume (append new metrics)"
echo "  --shutdown:  Stop all containers and remove data"
echo "  paths: Metrics files, directories, or glob patterns"
echo ""
echo "Examples:"
echo "  ./start-grafana.sh ~/benchmark-results/*/stats/"
echo "  ./start-grafana.sh ~/benchmark-results/20260216-*/stats/metrics-*.txt"
echo "  ./start-grafana.sh --keep-data build/container/.../metrics.txt"