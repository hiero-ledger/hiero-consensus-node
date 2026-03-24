#!/bin/bash
# Stop Grafana + VictoriaMetrics and remove all associated data.
#
# Usage: stop-grafana.sh

set -e

NETWORK_NAME="metrics-network"
VM_CONTAINER="victoriametrics"
GRAFANA_CONTAINER="grafana"

echo "Shutting down metrics visualization stack..."
docker rm -f $VM_CONTAINER $GRAFANA_CONTAINER 2>/dev/null || true
docker volume rm victoria-metrics-data 2>/dev/null || true
docker network rm $NETWORK_NAME 2>/dev/null || true
echo "Done."
