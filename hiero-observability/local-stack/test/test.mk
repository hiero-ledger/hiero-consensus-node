# The end-to-end selftest. Included by the root Makefile so `make selftest`
# stays a single entry point - see docs/development.md for what it asserts
# and why.
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
selftest:
	@set -u; \
	trap '$(TEST_COMPOSE) down -v --remove-orphans >/dev/null 2>&1' EXIT INT TERM; \
	$(TEST_COMPOSE) up -d --wait --wait-timeout 120 $(TEST_UNITS) || exit 1; \
	$(TEST_COMPOSE) run --rm -T selftest-assert \
	  || { $(TEST_COMPOSE) logs --no-color --tail=100 alloy victoriametrics loki; exit 1; }
