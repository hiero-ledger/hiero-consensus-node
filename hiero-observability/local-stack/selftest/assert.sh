#!/bin/sh
# Selftest assertions. Runs INSIDE a container joined to the Compose network -
# never on the host, because Windows has neither sh nor curl. Services are
# therefore addressed by their internal names, so these assertions do not depend
# on port mapping or on which host ports a user remapped.
#
# Invoked by `make selftest`. Exits non-zero with every failing query printed.

VM="http://victoriametrics:8428"
LOKI="http://loki:3100"

# Generous on purpose: VictoriaMetrics' -search.latencyOffset hides freshly
# scraped points for 30s by default, and Alloy re-globs LOG_INCLUDE every 10s.
DEADLINE=120
FAILURES=0
CURL="curl -sS --connect-timeout 3 -m 10"

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

# ---------------------------------------------------------------------------

printf '\n=== local-stack selftest ===\n\n'

wait_for victoriametrics "$VM/health" || true
wait_for loki "$LOKI/ready" || true
wait_for alloy "http://alloy:12345/-/ready" || true

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

assert 'the log pipeline is visible as a healthy scrape target' \
	vm 'up{job="alloy"} == 1' ''

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

# ---------------------------------------------------------------------------

printf '\n'
if [ "$FAILURES" -ne 0 ]; then
	printf '=== selftest FAILED: %s assertion(s) ===\n\n' "$FAILURES"
	exit 1
fi
printf '=== selftest passed ===\n\n'
