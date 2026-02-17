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

# Default metrics files pattern if none provided
if [ ${#METRICS_ARGS[@]} -eq 0 ]; then
  METRICS_ARGS=(build/container/ConsensusLayerBenchmark/benchmark/node-*/data/stats/metrics.txt)
fi

# Expand arguments to actual files (handles directories, globs, and individual files)
METRICS_FILES=()
for arg in "${METRICS_ARGS[@]}"; do
  if [ -d "$arg" ]; then
    # If it's a directory, find all metrics*.txt files in it
    while IFS= read -r -d '' file; do
      METRICS_FILES+=("$file")
    done < <(find "$arg" -maxdepth 1 -type f -name 'metrics*.txt' -print0)
  elif [ -f "$arg" ]; then
    # If it's a file, add it directly
    METRICS_FILES+=("$arg")
  else
    # Try to expand as glob pattern
    for file in $arg; do
      if [ -f "$file" ]; then
        METRICS_FILES+=("$file")
      fi
    done
  fi
done

echo "Starting metrics visualization stack..."
echo "Metrics files to import: ${#METRICS_FILES[@]}"

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

# Import metrics data from all files (already in Prometheus format)
echo "Importing metrics..."
imported_count=0
total_lines=0
for metrics_file in "${METRICS_FILES[@]}"; do
  if [ -f "$metrics_file" ]; then
    echo "  Importing $(basename "$metrics_file")..."
    line_count=$(wc -l < "$metrics_file")
    curl -s -X POST http://localhost:8428/api/v1/import/prometheus --data-binary @"$metrics_file"
    if [ $? -eq 0 ]; then
      ((imported_count++))
      ((total_lines+=line_count))
      echo "    ✓ Imported $line_count metrics"
    else
      echo "    ⚠️  Failed to import"
    fi
  fi
done

if [ $imported_count -eq 0 ]; then
  echo "⚠️  No metrics imported. You can import later with:"
  echo "   curl -X POST http://localhost:8428/api/v1/import/prometheus --data-binary @metrics.txt"
else
  echo "✓ Imported $imported_count files successfully ($total_lines total metrics)"
fi

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

# Get list of all metrics from VictoriaMetrics
echo "Fetching available metrics..."
METRICS=$(curl -s "http://localhost:8428/api/v1/label/__name__/values" | grep -o '"[^"]*"' | tr -d '"' | grep -v "^$")

# Create dashboard with all metrics
echo "Creating Grafana dashboard..."
DASHBOARD_JSON=$(cat <<EOF
{
  "dashboard": {
    "uid": "consensus-metrics",
    "title": "Consensus Metrics Dashboard",
    "tags": ["consensus", "benchmark"],
    "timezone": "browser",
    "schemaVersion": 16,
    "version": 0,
    "refresh": "5s",
    "panels": [],
    "templating": {
      "list": [
        {
          "name": "node",
          "type": "query",
          "datasource": {"uid": "$DATASOURCE_UID"},
          "query": "label_values(node)",
          "refresh": 1,
          "multi": true,
          "includeAll": true,
          "current": {"value": "\${VAR_NODE}", "text": "\${VAR_NODE}"}
        },
        {
          "name": "metric",
          "type": "query",
          "datasource": {"uid": "$DATASOURCE_UID"},
          "query": "label_values(__name__)",
          "refresh": 1,
          "multi": false,
          "includeAll": false,
          "current": {"value": "secSC2T", "text": "secSC2T"}
        }
      ]
    },
    "panels": [
      {
        "id": 1,
        "title": "Metric: \${metric}",
        "type": "timeseries",
        "gridPos": {"h": 12, "w": 24, "x": 0, "y": 0},
        "datasource": {"uid": "$DATASOURCE_UID"},
        "targets": [
          {
            "expr": "\${metric}{node=~\"\$node\"}",
            "legendFormat": "{{node}}",
            "refId": "A"
          }
        ],
        "options": {
          "legend": {"displayMode": "table", "placement": "right", "calcs": ["mean", "max", "min"]},
          "tooltip": {"mode": "multi"}
        },
        "fieldConfig": {
          "defaults": {
            "unit": "none",
            "custom": {"drawStyle": "line", "lineInterpolation": "linear", "showPoints": "never"}
          }
        }
      }
    ]
  },
  "overwrite": true
}
EOF
)

curl -s -X POST http://admin:admin@localhost:3000/api/dashboards/db \
  -H "Content-Type: application/json" \
  -d "$DASHBOARD_JSON" > /dev/null

echo ""
echo "✓ Stack started successfully!"
echo ""
echo "Access points:"
echo "  Grafana Dashboard: http://localhost:3000/d/consensus-metrics (admin/admin)"
echo "  VictoriaMetrics:   http://localhost:8428"
echo ""
echo "Imported metrics: $imported_count files"
echo "Total unique metrics: $(echo "$METRICS" | wc -l | tr -d ' ')"
echo ""
echo "Dashboard features:"
echo "  - Variable 'node': Select which nodes to display"
echo "  - Variable 'metric': Select which metric to visualize"
echo "  - Auto-refresh every 5 seconds"
echo ""
echo "To import more metrics:"
echo "  curl -X POST http://localhost:8428/api/v1/import/prometheus -d @metrics.txt"
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