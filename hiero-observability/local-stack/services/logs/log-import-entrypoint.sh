#!/bin/sh
# log-import-entrypoint: runs Alloy against IMPORT_LOGS_DIR (mounted
# read-only at /logs by docker-compose.import.yml's log-importer service)
# long enough to tail it once, then exits. See scripts/import-logs.sh, the
# only supported way to invoke this, and README.md, "Configure logs
# ingestion" -> "Historical".
#
# Uses the exact same /etc/alloy/config.alloy the live `alloy` service runs
# (see docker-compose.yml: both mount ${ALLOY_CONFIG}), so a historical
# import is parsed identically - same multiline grouping, same
# LOG_TIMESTAMP_REGEX/FORMAT/LOCATION - just against a different directory
# and label set (IMPORT_LOG_INCLUDE/IMPORT_LOG_LABELS, wired to config.alloy's
# LOG_INCLUDE/LOG_LABELS by docker-compose.yml's environment block).
#
# No persisted --storage.path: config.alloy's tail_from_end defaults to
# false, so every invocation reads IMPORT_LOGS_DIR from byte zero regardless
# - there is nothing worth remembering between runs of a one-shot import.
set -eu

: "${IMPORT_WAIT_SECONDS:?IMPORT_WAIT_SECONDS must be set}"

alloy run --server.http.listen-addr=0.0.0.0:12345 \
	--storage.path=/var/lib/alloy/data /etc/alloy/config.alloy &
ALLOY_PID=$!

# Give Alloy this long to discover, tail, and push every matching file, then
# stop it - there is no "caught up" signal to poll for a plain file tailer,
# so a fixed wait is what scripts/import-logs.sh's --wait-seconds controls.
# Polled in 1s steps, rather than a single `sleep "$IMPORT_WAIT_SECONDS"`, so
# an Alloy that exits on its own well before the deadline - invalid
# IMPORT_LOG_LABELS JSON, a broken ALLOY_CONFIG override, etc. - is noticed
# immediately instead of only after the full wait.
_elapsed=0
while [ "$_elapsed" -lt "$IMPORT_WAIT_SECONDS" ] && kill -0 "$ALLOY_PID" 2>/dev/null; do
	sleep 1
	_elapsed=$((_elapsed + 1))
done

if kill -0 "$ALLOY_PID" 2>/dev/null; then
	# Still running after the full wait - the expected, deliberate shutdown
	# of a one-shot import that has had time to tail everything.
	kill "$ALLOY_PID" 2>/dev/null || true
	wait "$ALLOY_PID" 2>/dev/null || true
	exit 0
fi

# Alloy already exited by itself, before the deliberate shutdown above -
# propagate its exit status instead of masking a failed import as success.
set +e
wait "$ALLOY_PID"
STATUS=$?
set -e
exit "$STATUS"
