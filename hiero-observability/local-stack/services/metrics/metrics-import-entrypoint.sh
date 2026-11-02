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
# (METRICS_RETENTION) - and one too far in the future, over a label limit or
# on a malformed line - and still answers 204. The only trace is a counter
# per reason (`vm_rows_ignored_total{reason=...}`, `vm_rows_invalid_total`),
# so each file is bracketed by a read of them and the import fails loudly,
# naming the reason, if one moved - otherwise an old dump would "succeed"
# and then show an empty dashboard. (A NaN sample is dropped without any
# counter; it carries no value, so it is not reported.)
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

# One "counter value" line per way an imported sample can be dropped: every
# vm_rows_ignored_total reason, plus this endpoint's malformed lines. The
# response is held in a variable first so a failed read fails the import
# instead of reading as all zeroes.
drop_counters() {
	METRICS=$(curl -fsS "$VM/metrics") || return
	printf '%s\n' "$METRICS" \
		| awk '/^vm_rows_ignored_total\{|^vm_rows_invalid_total\{type="prometheus"\}/ { print $1, $NF }'
}

# "counter delta" for every counter that rose between two drop_counters reads.
moved_counters() {
	printf '%s\n--\n%s\n' "$1" "$2" | awk '
		$0 == "--" { after = 1; next }
		!after { before[$1] = $2; next }
		$2 > before[$1] { print $1, $2 - before[$1] }'
}

FILES=$(find /metrics -type f -name "$IMPORT_METRICS_INCLUDE" | sort)
if [ -z "$FILES" ]; then
	echo "error: no file under the source directory matches '$IMPORT_METRICS_INCLUDE'" >&2
	exit 1
fi

STATUS=0
while IFS= read -r FILE; do
	SAMPLES=$(grep -Evc '^[[:space:]]*(#|$)' "$FILE" || true)
	BEFORE=$(drop_counters)
	curl -sS --fail-with-body -H 'Content-Type: text/plain' \
		--data-binary @"$FILE" "$@" "$VM/api/v1/import/prometheus"
	# Every sample is checked and counted before the 204 comes back, but
	# VictoriaMetrics regenerates /metrics at most once a second, and the read
	# above may have just cached it. Any read more than 1s after the 204 is
	# current, whatever the file size or load.
	sleep 2
	AFTER=$(drop_counters)
	MOVED=$(moved_counters "$BEFORE" "$AFTER")
	if [ -n "$MOVED" ]; then
		DROPPED=$(printf '%s\n' "$MOVED" | awk '{ s += $2 } END { print s }')
		echo "FAIL  ${FILE#/metrics/}: VictoriaMetrics dropped $DROPPED of $SAMPLES samples" >&2
		while read -r COUNTER N; do
			case "$COUNTER" in
				*'"small_timestamp"'*)
					WHY="older than METRICS_RETENTION - raise it in local.env, run 'make restart', and re-import" ;;
				*'"big_timestamp"'*) WHY="too far in the future" ;;
				vm_rows_invalid_total*) WHY="on malformed lines (not Prometheus text format)" ;;
				*) WHY="rejected, see $COUNTER and the victoriametrics logs" ;;
			esac
			echo "      $N $WHY" >&2
		done <<MOVED
$MOVED
MOVED
		STATUS=1
	else
		echo "OK    ${FILE#/metrics/}: $SAMPLES samples"
	fi
done <<EOF
$FILES
EOF
exit "$STATUS"
