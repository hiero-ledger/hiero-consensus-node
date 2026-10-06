#!/bin/sh
# Selftest assertions. Runs INSIDE a container joined to the Compose network -
# never on the host, because Windows has neither sh nor curl. Services are
# therefore addressed by their internal names, so these assertions do not depend
# on port mapping or on which host ports a user remapped.
#
# Invoked by `make selftest`. Exits non-zero with every failing query printed.

VM="http://victoriametrics:8428"
LOKI="http://loki:3100"
GRAFANA="http://grafana:3000"

# Generous on purpose: VictoriaMetrics' -search.latencyOffset hides freshly
# scraped points for 30s by default, and Alloy re-globs LOG_INCLUDE every 10s.
DEADLINE=120
FAILURES=0
CURL="curl -sS --connect-timeout 3 -m 10"

# 5s of slack for the log-timestamp-fidelity check below - clock skew
# between containers, not test flakiness. The fixture line this compares
# against is backdated 5 minutes (test/docker-compose.test.yml), so a
# regression back to Loki's default ingestion-time stamping - which would
# land within a few seconds of "now", not 5 minutes behind it - is caught
# with room to spare either way.
TIMESTAMP_TOLERANCE_NS=5000000000

# ---------------------------------------------------------------------------

wait_for() {
	_name=$1
	_url=$2
	_end=$(($(date +%s) + 90))
	while :; do
		if $CURL -o /dev/null -f "$_url" 2>/dev/null; then
			return 0
		fi
		if [ "$(date +%s)" -ge "$_end" ]; then
			printf 'FAIL  %s never became ready at %s\n' "$_name" "$_url"
			FAILURES=$((FAILURES + 1))
			return 1
		fi
		sleep 2
	done
}

q_vm() {
	# shellcheck disable=SC2086
	$CURL -G "$VM/api/v1/query" \
		--data-urlencode "query=$1" \
		${LATENCY_ARG:+--data-urlencode "$LATENCY_ARG"}
}

q_loki() {
	# shellcheck disable=SC2086
	$CURL -G "$LOKI/loki/api/v1/query_range" \
		--data-urlencode "query=$1" \
		--data-urlencode "since=1h" \
		--data-urlencode "limit=20" \
		--data-urlencode "direction=backward"
}

# Same as q_loki but with an explicit [start,end] (nanosecond epoch) instead
# of "since=1h" - used to reach further back than q_loki's window, to prove
# an old fixture entry is queryable once its chunk has flushed (see
# assert_old_entry_queryable).
q_loki_range() {
	_start_ns=$1
	_end_ns=$2
	# shellcheck disable=SC2086
	$CURL -G "$LOKI/loki/api/v1/query_range" \
		--data-urlencode "query=$3" \
		--data-urlencode "start=$_start_ns" \
		--data-urlencode "end=$_end_ns" \
		--data-urlencode "limit=20" \
		--data-urlencode "direction=backward"
}

# A successful response whose result set is not empty. Deliberately negative:
# grepping for '"result":[{' would bet on the server never pretty-printing.
non_empty() {
	printf '%s' "$1" | grep -q '"status":"success"' || return 1
	printf '%s' "$1" | grep -q '"result":\[\]' && return 1
	return 0
}

# assert <description> <vm|loki> <query> [extra grep pattern over the body]
assert() {
	_desc=$1
	_kind=$2
	_query=$3
	_extra=$4
	_end=$(($(date +%s) + DEADLINE))
	_body=""
	while :; do
		if [ "$_kind" = "vm" ]; then
			_body=$(q_vm "$_query" 2>/dev/null)
		else
			_body=$(q_loki "$_query" 2>/dev/null)
		fi
		if non_empty "$_body"; then
			if [ -z "$_extra" ] || printf '%s' "$_body" | grep -q "$_extra"; then
				printf 'PASS  %s\n' "$_desc"
				return 0
			fi
		fi
		if [ "$(date +%s)" -ge "$_end" ]; then
			break
		fi
		sleep 3
	done
	printf 'FAIL  %s\n' "$_desc"
	printf '      query: %s\n' "$_query"
	[ -n "$_extra" ] && printf '      expected body to match: %s\n' "$_extra"
	printf '      body: %s\n' "$(printf '%s' "$_body" | head -c 600)"
	FAILURES=$((FAILURES + 1))
}

# Proves Loki's reported entry timestamp came from the log line's own
# content, not from when Alloy ingested it. The fixture line embeds the
# exact epoch second it was written at (selftest-log-writer's
# "timestamp-check" line), so nothing needs to be shared between
# containers: both numbers compared below come out of the *same* Loki
# response. No jq available, so the two JSON fields are pulled out with sed.
assert_log_timestamp() {
	_desc='the entry timestamp comes from the log line, not ingestion time'
	_query='{environment="selftest"} |= "selftest timestamp-check"'
	_end=$(($(date +%s) + DEADLINE))
	_body=""
	_got_ns=""
	_want_ns=""
	while :; do
		_body=$(q_loki "$_query" 2>/dev/null)
		if non_empty "$_body"; then
			# Loki's query_range values are ["<epoch_ns>","<line>"] tuples;
			# pull the first one out as "<epoch_ns>|<line>".
			_pair=$(printf '%s' "$_body" \
				| sed -n 's/.*"values":\[\["\([0-9][0-9]*\)","\([^"]*\)".*/\1|\2/p')
			_got_ns=${_pair%%|*}
			_line=${_pair#*|}
			_epoch=$(printf '%s' "$_line" | sed -n 's/.*epoch=\([0-9][0-9]*\).*/\1/p')
			if [ -n "$_got_ns" ] && [ -n "$_epoch" ]; then
				_want_ns="${_epoch}000000000"
				_diff=$((_got_ns - _want_ns))
				[ "$_diff" -lt 0 ] && _diff=$((0 - _diff))
				if [ "$_diff" -le "$TIMESTAMP_TOLERANCE_NS" ]; then
					printf 'PASS  %s\n' "$_desc"
					return 0
				fi
			fi
		fi
		if [ "$(date +%s)" -ge "$_end" ]; then
			break
		fi
		sleep 3
	done
	printf 'FAIL  %s\n' "$_desc"
	printf '      query: %s\n' "$_query"
	printf '      got entry timestamp (ns):     %s\n' "$_got_ns"
	printf '      expected from log line epoch:  %s\n' "$_want_ns"
	printf '      body: %s\n' "$(printf '%s' "$_body" | head -c 600)"
	FAILURES=$((FAILURES + 1))
}

# Proves services/logs/loki-config.yml's lowered ingester.chunk_idle_period
# works: the fixture's "selftest historical-check" line
# (test/docker-compose.test.yml) is timestamped ~49h in the past, in its own
# stream that stops receiving writes immediately (a stand-in for a small,
# already-finished historical import). A stream's chunk only becomes
# queryable from an old time range once it flushes out of the ingester's
# memory into the filesystem store - with chunk_idle_period left at Loki's
# 30m default, this assertion would only pass after that delay; lowered to
# 1m, DEADLINE below (120s) comfortably covers the wait.
assert_old_entry_queryable() {
	_desc='an entry timestamped ~49h in the past becomes queryable once its chunk flushes (ingester.chunk_idle_period)'
	_query='{log_name="historical-check"}'
	_now_ns=$(($(date +%s) * 1000000000))
	_start_ns=$((_now_ns - 180000000000000))
	_end=$(($(date +%s) + DEADLINE))
	_body=""
	while :; do
		_body=$(q_loki_range "$_start_ns" "$_now_ns" "$_query" 2>/dev/null)
		if non_empty "$_body"; then
			printf 'PASS  %s\n' "$_desc"
			return 0
		fi
		if [ "$(date +%s)" -ge "$_end" ]; then
			break
		fi
		sleep 3
	done
	printf 'FAIL  %s\n' "$_desc"
	printf '      query: %s\n' "$_query"
	printf '      range: [%s, %s]\n' "$_start_ns" "$_now_ns"
	printf '      body: %s\n' "$(printf '%s' "$_body" | head -c 600)"
	FAILURES=$((FAILURES + 1))
}

# ---------------------------------------------------------------------------

printf '\n=== observability-stack selftest ===\n\n'

wait_for victoriametrics "$VM/health" || true
wait_for loki "$LOKI/ready" || true
wait_for alloy "http://alloy:12345/-/ready" || true
wait_for grafana "$GRAFANA/api/health" || true

# Ask VictoriaMetrics to bypass its 30s search latency offset, but only if this
# build accepts the argument - otherwise the DEADLINE above absorbs it.
LATENCY_ARG="latency_offset=1s"
if ! q_vm 'vector(1)' 2>/dev/null | grep -q '"status":"success"'; then
	LATENCY_ARG=""
	printf 'note  latency_offset not accepted; relying on the %ss deadline\n\n' "$DEADLINE"
fi

# --- metrics ---------------------------------------------------------------

# Each metric name is queried verbatim. This is the check that catches a
# translation layer renaming things in transit: the `_total` suffix, the
# camelCase `blockStream` segment, and a name production dashboards use as-is.
# The label matcher folds in the "METRIC_LABELS reached every series" check, and
# `== <value>` folds in "the scraped value is intact".
assert 'counter name survives verbatim, with METRIC_LABELS applied' \
	vm 'selftest_requests_total{environment="selftest",type="max"} == 42' ''

assert 'camelCase metric name survives verbatim' \
	vm 'selftest_blockStream_round_duration_seconds{environment="selftest"} == 0.25' ''

assert 'dashboard-style metric name survives verbatim' \
	vm 'selftest_platform_trans_per_sec{environment="selftest"} == 17' ''

assert 'the fixture scrape target is up' \
	vm 'up{job="selftest"} == 1' ''

# --- logs ------------------------------------------------------------------

# Selecting on a LOG_LABELS key proves those labels became real *stream*
# labels, not metadata.
assert 'LOG_LABELS became stream labels' \
	loki '{environment="selftest"}' ''

assert 'log_name is derived from the file basename' \
	loki '{log_name="selftest"}' ''

# Line filters match against the whole entry, embedded newlines included, so
# these two can only both match if multi-line grouping merged the exception
# header and the third stack frame into ONE entry. Without stage.multiline this
# returns an empty result. The extra pattern confirms the tabs survived too.
assert 'a stack trace is grouped into a single multi-line entry' \
	loki '{environment="selftest"} |= "java.lang.RuntimeException" |= "com.example.Gamma"' \
	'\\tat com\.example\.Gamma'

# The timestamp comparison below is the actual, end-to-end proof that
# config.alloy's stage.regex/stage.timestamp addition works: it fails loudly
# if the pipeline ever regresses to stamping entries with ingestion time.
assert_log_timestamp

assert_old_entry_queryable

# scripts/import-logs.sh ran against selftest-import-src before this script
# started (see test.mk), with an explicit import_check label and the same
# selftest.log basename as selftest-log-writer's fixture above. Selecting on
# that label - not just log_name - proves the import landed as its own
# stream rather than colliding with (or being rejected behind) the live
# fixture's identically-named stream.
assert 'import-logs.sh landed its entries under their own explicit label' \
	loki '{log_name="selftest", import_check="1"} |= "selftest import-check"' ''

# --- dashboards (issue 3) ----------------------------------------------

# Grafana provisions dashboards at startup; poll rather than assume it's
# instantaneous once the container is merely "running".
_end=$(($(date +%s) + DEADLINE))
_body=""
while :; do
	_body=$($CURL "$GRAFANA/api/dashboards/uid/selftest-binding" 2>/dev/null)
	if printf '%s' "$_body" | grep -q '"uid":"selftest-binding"'; then
		break
	fi
	if [ "$(date +%s)" -ge "$_end" ]; then
		break
	fi
	sleep 3
done

if printf '%s' "$_body" | grep -q '"uid":"selftest-binding"'; then
	if printf '%s' "$_body" | grep -q '\${'; then
		printf 'FAIL  dashboard placeholder rewrite: unresolved ${...} survives in provisioned dashboard\n'
		FAILURES=$((FAILURES + 1))
	else
		printf 'PASS  dashboard placeholder rewrite: no ${...} left in provisioned dashboard\n'
	fi

	if printf '%s' "$_body" | grep -q "\"uid\":\"$METRICS_DATASOURCE_NAME\""; then
		printf 'PASS  dashboard panel datasource bound to METRICS_DATASOURCE_NAME (%s)\n' "$METRICS_DATASOURCE_NAME"
	else
		printf 'FAIL  dashboard panel datasource not bound to METRICS_DATASOURCE_NAME (%s)\n' "$METRICS_DATASOURCE_NAME"
		FAILURES=$((FAILURES + 1))
	fi
else
	printf 'FAIL  provisioned dashboard "selftest-binding" never appeared at %s\n' "$GRAFANA/api/dashboards/uid/selftest-binding"
	FAILURES=$((FAILURES + 1))
fi

# ---------------------------------------------------------------------------

printf '\n'
if [ "$FAILURES" -ne 0 ]; then
	printf '=== selftest FAILED: %s assertion(s) ===\n\n' "$FAILURES"
	exit 1
fi
printf '=== selftest passed ===\n\n'
