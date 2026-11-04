# The end-to-end selftest. Included by the root Makefile so `make selftest`
# stays a single entry point - see README.md, "Run the automated end-to-end
# assertions", for what it asserts and why, and test/assert.sh for the
# assertions themselves.
#
# Layers test/docker-compose.test.yml on top of the base docker-compose.yml
# and runs as its own Compose project, so it cannot disturb a running stack.
# Deliberately does not read local.env: it asserts that the *committed*
# defaults work, not that one developer's configuration does.
TEST_COMPOSE := docker compose -p observability-stack-selftest -f docker-compose.yml -f test/docker-compose.test.yml --env-file defaults.env --env-file test/selftest.env
TEST_UNITS   := victoriametrics loki alloy grafana selftest-metrics selftest-log-writer

.PHONY: selftest

# --wait fails if a container exits, so the fixtures stay alive. The trap
# guarantees teardown on every path, including a failing `up`, and the backend
# logs are dumped before teardown so a failure is debuggable.
#
# metrics-importer runs on the same file twice, on purpose: assert.sh then
# checks the samples were stored once. It must also fail, naming
# METRICS_RETENTION, on a sample older than retention, and fail on invalid
# labels.
selftest:
	@set -u; \
	trap '$(TEST_COMPOSE) down -v --remove-orphans >/dev/null 2>&1' EXIT INT TERM; \
	$(TEST_COMPOSE) up -d --wait --wait-timeout 120 $(TEST_UNITS) || exit 1; \
	$(TEST_COMPOSE) run --rm selftest-import-writer || exit 1; \
	IMPORT_LOGS_DIR=selftest-import-src IMPORT_LOG_INCLUDE='/logs/**/*.log' \
	IMPORT_LOG_LABELS='{"environment":"selftest","import_check":"1"}' IMPORT_WAIT_SECONDS=15 \
	  $(TEST_COMPOSE) -f docker-compose.import.yml --profile import run --rm log-importer \
	  || { $(TEST_COMPOSE) -f docker-compose.import.yml logs --no-color --tail=100 log-importer; exit 1; }; \
	IMPORT_LOGS_DIR=selftest-import-src IMPORT_LOG_INCLUDE='/logs/**/*.log' \
	IMPORT_LOG_LABELS='not-json' IMPORT_WAIT_SECONDS=15 \
	  $(TEST_COMPOSE) -f docker-compose.import.yml --profile import run --rm log-importer \
	  && { echo 'expected log-importer to fail on invalid IMPORT_LOG_LABELS, but it exited 0'; exit 1; }; \
	$(TEST_COMPOSE) run --rm selftest-metrics-import-writer || exit 1; \
	for _ in 1 2; do \
	  IMPORT_METRICS_DIR=selftest-metrics-import-src IMPORT_METRICS_INCLUDE=import-check.txt \
	  IMPORT_METRICS_LABELS='{"environment":"selftest","node_id":"1"}' \
	    $(TEST_COMPOSE) -f docker-compose.import.yml --profile import run --rm metrics-importer \
	    || exit 1; \
	done; \
	_out=$$(IMPORT_METRICS_DIR=selftest-metrics-import-src IMPORT_METRICS_INCLUDE=too-old.txt \
	  IMPORT_METRICS_LABELS='{"environment":"selftest"}' \
	  $(TEST_COMPOSE) -f docker-compose.import.yml --profile import run --rm -T metrics-importer 2>&1) \
	  && { printf '%s\n' "$$_out"; echo 'expected metrics-importer to fail on a sample older than METRICS_RETENTION, but it exited 0'; exit 1; }; \
	printf '%s\n' "$$_out" | grep -q 'older than METRICS_RETENTION' \
	  || { printf '%s\n' "$$_out"; echo 'expected metrics-importer to name METRICS_RETENTION as the reason it failed'; exit 1; }; \
	IMPORT_METRICS_DIR=selftest-metrics-import-src IMPORT_METRICS_INCLUDE=import-check.txt \
	IMPORT_METRICS_LABELS='not-json' \
	  $(TEST_COMPOSE) -f docker-compose.import.yml --profile import run --rm metrics-importer \
	  && { echo 'expected metrics-importer to fail on invalid IMPORT_METRICS_LABELS, but it exited 0'; exit 1; }; \
	$(TEST_COMPOSE) run --rm -T selftest-assert \
	  || { $(TEST_COMPOSE) logs --no-color --tail=100 alloy victoriametrics loki; exit 1; }
