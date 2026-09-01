# Convenience wrapper around docker compose.
#
# `make` is optional and never required - the README documents the raw
# `docker compose` equivalent of every target, which is what Windows users
# without make should use.

COMPOSE  := docker compose --env-file defaults.env --env-file local.env

# The selftest runs as its own Compose project so it cannot disturb a running
# stack, and deliberately without local.env: it asserts that the *committed*
# defaults work, not that one developer's configuration does.
SELFTEST := docker compose -p local-stack-selftest --env-file defaults.env --env-file selftest/selftest.env
ST_UNITS := victoriametrics loki alloy selftest-metrics selftest-log-writer

.PHONY: help up down reset restart logs ps selftest

help:
	@printf 'Targets:\n'
	@printf '  up        start the stack\n'
	@printf '  down      stop the stack, keep the data\n'
	@printf '  reset     stop the stack and delete all stored data\n'
	@printf '  restart   recreate the containers (after a config change)\n'
	@printf '  logs      follow the stack logs\n'
	@printf '  ps        show container status\n'
	@printf '  selftest  run the end-to-end assertions\n'

# The second --env-file fails outright if the file is absent.
local.env:
	@touch local.env

up: local.env
	@mkdir -p logs
	$(COMPOSE) up -d
	@printf '\nGrafana      http://localhost:%s\n' "$$(grep -E '^GRAFANA_PORT=' defaults.env local.env | tail -1 | cut -d= -f2)"
	@printf 'VM targets   http://localhost:%s/targets\n\n' "$$(grep -E '^VICTORIAMETRICS_PORT=' defaults.env local.env | tail -1 | cut -d= -f2)"

down: local.env
	$(COMPOSE) down

reset: local.env
	$(COMPOSE) down -v

restart: local.env
	$(COMPOSE) up -d --force-recreate

logs: local.env
	$(COMPOSE) logs -f

ps: local.env
	$(COMPOSE) ps

# --wait fails if a container exits, so the fixtures stay alive. The trap
# guarantees teardown on every path, including a failing `up`, and the backend
# logs are dumped before teardown so a failure is debuggable.
selftest:
	@set -u; \
	trap '$(SELFTEST) --profile selftest --profile assert down -v --remove-orphans >/dev/null 2>&1' EXIT INT TERM; \
	$(SELFTEST) --profile selftest up -d --wait --wait-timeout 120 $(ST_UNITS) || exit 1; \
	$(SELFTEST) --profile selftest --profile assert run --rm -T selftest-assert \
	  || { $(SELFTEST) logs --no-color --tail=100 alloy victoriametrics loki; exit 1; }
