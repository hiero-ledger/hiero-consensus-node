#!/bin/sh
# metrics-import-entrypoint: posts every file under /metrics (mounted
# read-only by docker-compose.import.yml's metrics-importer service)
# whose basename matches IMPORT_METRICS_INCLUDE to VictoriaMetrics' own
# /api/v1/import/prometheus endpoint, tagging every series with the labels
# in IMPORT_METRICS_LABELS. See scripts/import-metrics.sh, the only
# supported way to invoke this, and README.md, "Configure metrics scraping"
# -> "Historical".
#
# That endpoint takes the Prometheus text exposition format *with* its
# optional per-sample millisecond timestamp, e.g.
#   platform_trans_per_sec{node="0"} 17.0 1771419351507
# so a dump written that way lands at the times it was recorded, not at the
# time of the import. Labels are added through `extra_label` query args
# rather than by rewriting the file, so the source is never touched and a
# multi-megabyte dump is streamed as-is.
#
# VictoriaMetrics silently drops a sample older than -retentionPeriod
# (METRICS_RETENTION) and still answers 204. The only trace is its
# `vm_rows_ignored_total` counter, so each file is bracketed by a read of it
# and the import fails loudly if it moved - otherwise an old dump would
# "succeed" and then show an empty dashboard.
set -eu

: "${IMPORT_METRICS_LABELS:?IMPORT_METRICS_LABELS must be set}"
: "${IMPORT_METRICS_INCLUDE:?IMPORT_METRICS_INCLUDE must be set}"

VM="http://victoriametrics:8428"

# Flat JSON map of string values -> one `--url-query extra_label=k=v` per
# entry (curl URL-encodes the content, `=` included). There is no jq in this
# image, so the map is split with sed; anything that is not a plain
# "name":"value" pair is rejected up front rather than half-parsed.
LABEL_RE='^"[A-Za-z_][A-Za-z0-9_]*"[[:space:]]*:[[:space:]]*"[^"\\]*"$'
set --
PAIRS=$(printf '%s' "$IMPORT_METRICS_LABELS" \
	| sed -e 's/^[[:space:]]*{[[:space:]]*//' -e 's/[[:space:]]*}[[:space:]]*$//' \
		-e 's/"[[:space:]]*,[[:space:]]*"/"\n"/g')
while IFS= read -r PAIR; do
	if ! printf '%s' "$PAIR" | grep -Eq "$LABEL_RE"; then
		echo "error: invalid IMPORT_METRICS_LABELS entry: '$PAIR'" >&2
		echo "expected a flat JSON map of string values, e.g. {\"node_id\":\"1\"}" >&2
		exit 1
	fi
	K=$(printf '%s' "$PAIR" | sed -e 's/^"\([^"]*\)".*/\1/')
	V=$(printf '%s' "$PAIR" | sed -e 's/^"[^"]*"[[:space:]]*:[[:space:]]*"\(.*\)"$/\1/')
	set -- "$@" --url-query "extra_label=$K=$V"
done <<EOF
$PAIRS
EOF

ignored_rows() {
	curl -fsS "$VM/metrics" | awk '/^vm_rows_ignored_total/ { s += $NF } END { print s + 0 }'
}

FILES=$(find /metrics -type f -name "$IMPORT_METRICS_INCLUDE" | sort)
if [ -z "$FILES" ]; then
	echo "error: no file under the source directory matches '$IMPORT_METRICS_INCLUDE'" >&2
	exit 1
fi

STATUS=0
while IFS= read -r FILE; do
	SAMPLES=$(grep -Evc '^[[:space:]]*(#|$)' "$FILE" || true)
	BEFORE=$(ignored_rows)
	curl -fsS --fail-with-body -H 'Content-Type: text/plain' \
		--data-binary @"$FILE" "$@" "$VM/api/v1/import/prometheus"
	# The timestamp check happens when VictoriaMetrics moves its pending rows
	# into storage, on a 1s timer, not during the import request itself - so
	# the counter only reflects this file after that. (/internal/force_flush
	# does not cover this stage.)
	sleep 2
	DROPPED=$(($(ignored_rows) - BEFORE))
	if [ "$DROPPED" -gt 0 ]; then
		echo "FAIL  ${FILE#/metrics/}: VictoriaMetrics dropped $DROPPED of $SAMPLES samples" >&2
		echo "      They are older than METRICS_RETENTION (or too far in the future)." >&2
		echo "      Raise METRICS_RETENTION in local.env, run 'make restart', and re-import." >&2
		STATUS=1
	else
		echo "OK    ${FILE#/metrics/}: $SAMPLES samples"
	fi
done <<EOF
$FILES
EOF
exit "$STATUS"
