#!/bin/sh
# One-shot historical log import with explicit, caller-supplied labels,
# independent of the always-on LOGS_DIR tail (see docker-compose.yml's
# `alloy` service). Use this instead of dropping files straight into
# LOGS_DIR whenever two sources could share a basename - a re-imported
# older run next to a newer one, or several nodes' identically-named log
# files - since LOGS_DIR alone gives every file with the same basename the
# same `log_name` stream regardless of which directory it came from. See
# README.md, "Configure logs ingestion" -> "Historical".
#
# Reuses the live stack's own services/logs/config.alloy (or your ALLOY_CONFIG
# override) and LOG_TIMESTAMP_REGEX/FORMAT/LOCATION, so this parses a file
# exactly the way the live tailer would - see docker-compose.import.yml's
# log-importer service for why this runs a second Alloy instead of pushing
# to Loki directly.
#
# Usage: ./scripts/import-logs.sh <source-dir> '<labels-json>' [glob] [wait-seconds]
#   <source-dir>   required, a HOST path - mounted read-only, exactly like
#                  LOGS_DIR. May be relative or absolute; a relative path is
#                  resolved against your current directory, not against
#                  local-stack/ - run this script from anywhere.
#   <labels-json>  required, a JSON map - exactly like LOG_LABELS. Include
#                  `environment` yourself if you want these entries to match
#                  the live stack's LOG_LABELS.
#   [glob]         optional, a container-side path as LOG_INCLUDE is
#                  (defaults to /logs/**/*.log).
#   [wait-seconds] optional, how long to let Alloy tail before stopping it
#                  (defaults to 30 - raise it for a large backfill).
#
# Examples:
#   ./scripts/import-logs.sh /path/to/logs/node0 '{"node_id":"1"}'
#   ./scripts/import-logs.sh /path/to/logs/run1 '{"run":"1"}'
#   path/to/local-stack/scripts/import-logs.sh ./node0 '{"node_id":"1"}'  # from elsewhere
#
# Requires the main stack already running (`make up`) - this talks to the
# same `loki` service over the `observability` network.
set -eu

SRC=${1:-}
LABELS=${2:-}
if [ -z "$SRC" ] || [ -z "$LABELS" ]; then
	echo "usage: $0 <source-dir> '<labels-json>' [glob] [wait-seconds]" >&2
	exit 1
fi

# Resolve a relative <source-dir> against the caller's cwd *before* the `cd`
# below moves us into local-stack/ - otherwise docker compose would (wrongly)
# resolve it against local-stack/ instead, since that's the project directory
# it infers from -f docker-compose.yml's own path once we're there.
case "$SRC" in
	/*) ;;
	*) SRC="$PWD/$SRC" ;;
esac

cd "$(dirname "$0")/.."

export IMPORT_LOGS_DIR="$SRC"
export IMPORT_LOG_LABELS="$LABELS"
export IMPORT_LOG_INCLUDE="${3:-/logs/**/*.log}"
export IMPORT_WAIT_SECONDS="${4:-30}"

docker compose --env-file defaults.env --env-file local.env \
	-f docker-compose.yml -f docker-compose.import.yml \
	--profile import run --rm log-importer
