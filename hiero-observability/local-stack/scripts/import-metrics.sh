#!/bin/sh
# One-shot historical metrics import with explicit, caller-supplied labels,
# independent of the always-on scraping (see docker-compose.yml's
# `victoriametrics` service and METRIC_LABELS). Use this to load a finished
# run's metrics dump - Prometheus text format with per-sample millisecond
# timestamps, one `name{labels} value timestamp` per line - so the samples
# land at the times they were recorded. Because every file in one invocation
# gets the same labels, run it once per node (or per run) whose series must
# stay distinguishable. See README.md, "Configure metrics scraping" ->
# "Historical".
#
# Posts to the live stack's own VictoriaMetrics import API, so the data lands
# in the same store the dashboards query. Runs as the metrics-importer service
# in docker-compose.import.yml - a one-shot container, so the host needs no
# curl and the stack's host ports play no part.
#
# Usage: ./scripts/import-metrics.sh <source-dir> '<labels-json>' [file-pattern]
#   <source-dir>    required, a HOST path - mounted read-only. May be relative
#                   or absolute; a relative path is resolved against your
#                   current directory, not against local-stack/ - run this
#                   script from anywhere. Searched recursively.
#   <labels-json>   required, a flat JSON map of string values - exactly like
#                   METRIC_LABELS. Added to every series; it overrides a label
#                   of the same name already present in the file. Include
#                   `environment` yourself if you want these series to match
#                   the live stack's METRIC_LABELS.
#   [file-pattern]  optional, a basename pattern as `find -name` takes it
#                   (defaults to *.txt). Use it to pick one node's file out of
#                   a directory holding several.
#
# Examples:
#   ./scripts/import-metrics.sh /path/to/stats '{"environment":"localhost","node_id":"0"}' metrics-node-0.txt
#   ./scripts/import-metrics.sh /path/to/stats '{"environment":"localhost","node_id":"1"}' metrics-node-1.txt
#   path/to/local-stack/scripts/import-metrics.sh ./stats '{"run":"1"}'  # from elsewhere
#
# Requires the main stack already running (`make up`) - this talks to the
# same `victoriametrics` service over the `observability` network. Samples
# older than METRICS_RETENTION are dropped by VictoriaMetrics; the import
# fails and says so rather than reporting success.
set -eu

SRC=${1:-}
LABELS=${2:-}
if [ -z "$SRC" ] || [ -z "$LABELS" ]; then
	echo "usage: $0 <source-dir> '<labels-json>' [file-pattern]" >&2
	exit 1
fi

# Resolve a relative <source-dir> against the caller's cwd *before* the `cd`
# below moves us into local-stack/ - see import-logs.sh for why.
case "$SRC" in
	/*) ;;
	*) SRC="$PWD/$SRC" ;;
esac

# Docker would otherwise create a missing bind-mount source (as root, on
# Linux) and the import would then fail with a confusing "no file matches".
if [ ! -d "$SRC" ]; then
	echo "error: source directory not found: $SRC" >&2
	exit 1
fi

cd "$(dirname "$0")/.."

export IMPORT_METRICS_DIR="$SRC"
export IMPORT_METRICS_LABELS="$LABELS"
export IMPORT_METRICS_INCLUDE="${3:-*.txt}"

docker compose --env-file defaults.env --env-file local.env \
	-f docker-compose.yml -f docker-compose.import.yml \
	--profile import run --rm metrics-importer
