#!/usr/bin/env bash
#
# Start one local Hiero node for CLPR e2e testing.
#
# Usage:
#   ./start-hiero-local.sh        # start in background
#   ./start-hiero-local.sh down   # stop the background process
#   ./start-hiero-local.sh logs   # tail logs
#   ./start-hiero-local.sh status
#
# By default this starts from a fresh local node state. Set
# KEEP_HIERO_STATE=true to preserve saved state, block streams, and output logs.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
RUN_DIR="${SCRIPT_DIR}/.hiero-run"
PID_FILE="${RUN_DIR}/hiero.pid"
LOG_FILE="${RUN_DIR}/hiero.log"
NODE_WORK_DIR="${REPO_ROOT}/hedera-node/hedera-app/build/node"
STARTUP_ASSETS_DIR="${REPO_ROOT}/startup-assets"
GENESIS_NETWORK_SOURCE="${GENESIS_NETWORK_SOURCE:-${REPO_ROOT}/hedera-node/configuration/dev/genesis-network.json}"
SCREEN_SESSION="${HIERO_SCREEN_SESSION:-clpr-hiero-node}"
HAPI_PORT="${HAPI_PORT:-50211}"
GOSSIP_PORT="${GOSSIP_PORT:-31013}"
METRICS_PORT="${METRICS_PORT:-9999}"
HIERO_GRADLE_TASK="${HIERO_GRADLE_TASK:-:app:run}"
CMD="${1:-up}"

DEFAULT_JDK25="/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home"
if [[ -x "${DEFAULT_JDK25}/bin/java" ]]; then
    export JAVA_HOME="${JAVA_HOME:-${DEFAULT_JDK25}}"
fi

die() { echo "FAIL $*" >&2; exit 1; }
note() { echo "$*" >&2; }

screen_session_id() {
    command -v screen >/dev/null 2>&1 || return 0
    local sessions
    sessions="$(screen -ls 2>/dev/null || true)"
    awk -v suffix=".${SCREEN_SESSION}" '
        index($1, suffix) { print $1; exit }
    ' <<< "${sessions}"
}

is_screen_running() {
    [[ -n "$(screen_session_id)" ]]
}

is_pid_running() {
    [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" >/dev/null 2>&1
}

is_running() {
    is_screen_running || is_pid_running
}

kill_pids() {
    local label="$1"; shift
    local pids=("$@")
    [[ ${#pids[@]} -gt 0 ]] || return 0
    note "Stopping ${label}: ${pids[*]}"
    kill "${pids[@]}" >/dev/null 2>&1 || true
    sleep 2
    local live=()
    local pid
    for pid in "${pids[@]}"; do
        if kill -0 "${pid}" >/dev/null 2>&1; then
            live+=("${pid}")
        fi
    done
    if [[ ${#live[@]} -gt 0 ]]; then
        kill -9 "${live[@]}" >/dev/null 2>&1 || true
    fi
}

kill_local_listeners() {
    command -v lsof >/dev/null 2>&1 || return 0
    local pids
    pids="$({
        lsof -tiTCP:"${HAPI_PORT}" -sTCP:LISTEN 2>/dev/null || true
        lsof -tiTCP:"${GOSSIP_PORT}" -sTCP:LISTEN 2>/dev/null || true
        lsof -tiTCP:"${METRICS_PORT}" -sTCP:LISTEN 2>/dev/null || true
    })"
    if [[ -n "${pids}" ]]; then
        read -r -a pids_array <<< "$(printf '%s\n' "${pids}" | sort -u | tr '\n' ' ')"
        kill_pids "local Hiero listener(s)" "${pids_array[@]}"
    fi
}

kill_repo_processes() {
    local pids
    pids="$(ps -axo pid=,command= | awk -v root="${REPO_ROOT}" '
        index($0, root) && ($0 ~ /gradlew .*:app:run/ || $0 ~ /com\.hedera\.node\.app/ || $0 ~ / -local 0/) { print $1 }
    ')"
    if [[ -n "${pids}" ]]; then
        read -r -a pids_array <<< "$(printf '%s\n' "${pids}" | sort -u | tr '\n' ' ')"
        kill_pids "Hiero repo process(es)" "${pids_array[@]}"
    fi
}

wait_for_port() {
    local deadline=$((SECONDS + ${HAPI_WAIT_SECONDS:-180}))
    while (( SECONDS < deadline )); do
        if nc -z 127.0.0.1 "${HAPI_PORT}" >/dev/null 2>&1; then
            return 0
        fi
        sleep 2
    done
    return 1
}

clean_hiero_state() {
    if [[ "${KEEP_HIERO_STATE:-false}" == "true" ]]; then
        note "Preserving Hiero local state because KEEP_HIERO_STATE=true."
        return 0
    fi

    note "Cleaning Hiero local saved state/output under ${NODE_WORK_DIR}."
    rm -rf \
        "${NODE_WORK_DIR}/data/saved" \
        "${NODE_WORK_DIR}/data/blockStreams" \
        "${NODE_WORK_DIR}/data/recordStreams" \
        "${NODE_WORK_DIR}/data/stats" \
        "${NODE_WORK_DIR}/output" \
        "${NODE_WORK_DIR}/settingsUsed.txt"
}

ensure_genesis_network() {
    local target="${STARTUP_ASSETS_DIR}/genesis-network.json"
    if [[ -f "${target}" ]]; then
        return 0
    fi
    [[ -f "${GENESIS_NETWORK_SOURCE}" ]] || die "missing genesis network source ${GENESIS_NETWORK_SOURCE}"
    mkdir -p "${STARTUP_ASSETS_DIR}"
    cp "${GENESIS_NETWORK_SOURCE}" "${target}"
    note "Seeded ${target} from ${GENESIS_NETWORK_SOURCE}."
}

port_in_use() {
    local port="$1"
    nc -z 127.0.0.1 "${port}" >/dev/null 2>&1
}

case "${CMD}" in
    down)
        if is_screen_running; then
            note "Stopping screen session ${SCREEN_SESSION}."
            screen -S "${SCREEN_SESSION}" -X quit >/dev/null 2>&1 || true
            sleep 2
        fi
        if is_pid_running; then
            kill_pids "Hiero wrapper pid" "$(cat "${PID_FILE}")"
            note "Stopped Hiero pid from ${PID_FILE}."
        else
            note "Hiero node is not running from ${PID_FILE}."
        fi
        rm -f "${PID_FILE}"
        kill_repo_processes
        kill_local_listeners
        exit 0
        ;;
    logs)
        mkdir -p "${RUN_DIR}"
        touch "${LOG_FILE}"
        exec tail -f "${LOG_FILE}"
        ;;
    status)
        if is_running; then
            if is_screen_running; then
                note "Hiero node screen=$(screen_session_id)"
            fi
            if is_pid_running; then
                note "Hiero node pid=$(cat "${PID_FILE}")"
            fi
            exit 0
        fi
        die "Hiero node is not running from ${PID_FILE}"
        ;;
    up) ;;
    *) die "usage: $0 [up|down|logs|status]" ;;
esac

command -v nc >/dev/null 2>&1 || die "nc not found on PATH"
[[ -x "${REPO_ROOT}/gradlew" ]] || die "missing ${REPO_ROOT}/gradlew"

if is_running; then
    note "Hiero node already running pid=$(cat "${PID_FILE}")"
    exit 0
fi

for port in "${HAPI_PORT}" "${GOSSIP_PORT}" "${METRICS_PORT}"; do
    if port_in_use "${port}"; then
        die "127.0.0.1:${port} is already in use. Run '$0 down' or stop the existing Hiero node."
    fi
done

mkdir -p "${RUN_DIR}"
if [[ -z "${TSS_LIB_WRAPS_ARTIFACTS_PATH:-}" && -d "${HOME}/Documents/wraps-v1.0.0" ]]; then
    export TSS_LIB_WRAPS_ARTIFACTS_PATH="${HOME}/Documents/wraps-v1.0.0"
fi

clean_hiero_state
ensure_genesis_network

note "Starting Hiero node in background with ${HIERO_GRADLE_TASK}..."
if [[ -n "${TSS_LIB_WRAPS_ARTIFACTS_PATH:-}" ]]; then
    note "TSS preload: ${TSS_LIB_WRAPS_ARTIFACTS_PATH}"
fi
: > "${LOG_FILE}"
if command -v screen >/dev/null 2>&1; then
    note "Screen session: ${SCREEN_SESSION}"
    screen -dmS "${SCREEN_SESSION}" bash -lc \
        'cd "$1" && exec ./gradlew "$2" >> "$3" 2>&1' \
        _ "${REPO_ROOT}" "${HIERO_GRADLE_TASK}" "${LOG_FILE}"
    screen_pid="$(screen_session_id | cut -d. -f1)"
    if [[ -n "${screen_pid}" ]]; then
        echo "${screen_pid}" > "${PID_FILE}"
    fi
else
    (
        cd "${REPO_ROOT}"
        nohup ./gradlew "${HIERO_GRADLE_TASK}" >> "${LOG_FILE}" 2>&1 &
        echo $! > "${PID_FILE}"
    )
fi

if wait_for_port; then
    note "Hiero node is listening on 127.0.0.1:${HAPI_PORT}"
    note "Logs: ${LOG_FILE}"
else
    note "Hiero did not open ${HAPI_PORT} before timeout. Check logs: ${LOG_FILE}"
    exit 1
fi
