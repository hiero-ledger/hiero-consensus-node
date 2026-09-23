#!/usr/bin/env bash
#
# setup-clpr-ping-pong.sh
#
# Deploys the PingPong CLPR application contract on both networks (alice and
# bob) via yahcli `contracts create`. Captures and prints the resulting
# contract ids so they can be wired into `clpr send-message` as the
# `--target-application` on the peer side.
#
# Usage:
#   ./setup-clpr-ping-pong.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# yahcli.jar is built with class file 69.0 (JDK 25). Force JAVA_HOME if it
# points at an older JDK — the launcher (./yahcli) honours $JAVA_HOME.
DEFAULT_JDK25="/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home"
if [[ -x "${DEFAULT_JDK25}/bin/java" ]]; then
    export JAVA_HOME="${JAVA_HOME:-${DEFAULT_JDK25}}"
fi

# === Configuration =========================================================
NET_A="${NET_A:-alice}"
NET_B="${NET_B:-bob}"
PAYER="${PAYER:-2}"

PING_PONG_BIN="${PING_PONG_BIN:-${REPO_ROOT}/hedera-node/test-clients/src/main/resources/contract/contracts/PingPong/PingPong.bin}"

# 1,000,000 HBAR seed balance per deploy (1 HBAR = 1e8 tinybars → 1e14 tinybars).
# Funds PingPong's role as the connector contract so it can pay precompile/auth
# gas costs indefinitely without operator top-ups.
INITIAL_BALANCE_TINYBARS="${INITIAL_BALANCE_TINYBARS:-100000000000000}"

# ContractCreate gas. PingPong's runtime bytecode is ~3.5 KB; at 200 gas/byte
# for code deposit (EIP-3860) that's ~700k just for the deposit, plus constructor
# overhead. 1.5M gives comfortable headroom; bump if PingPong grows further.
CREATE_GAS="${CREATE_GAS:-1500000}"

LOG_DIR="${SCRIPT_DIR}/.run-logs"
mkdir -p "${LOG_DIR}"

# === Helpers ===============================================================
YELLOW=$'\033[1;33m'
CYAN=$'\033[1;36m'
GREEN=$'\033[1;32m'
RED=$'\033[1;31m'
MAGENTA=$'\033[1;35m'
RESET=$'\033[0m'

header() { printf "\n${CYAN}==> %s${RESET}\n" "$*"; }
step()   { printf "${YELLOW}  -- %s${RESET}\n" "$*"; }
ok()     { printf "${GREEN}     OK${RESET} %s\n" "$*"; }
die()    { printf "${RED}     FAIL${RESET} %s\n" "$*" >&2; exit 1; }

# Quote each argument so the displayed command is copy-pasteable.
shellquote() {
    local out=""
    for arg in "$@"; do
        if [[ "${arg}" =~ ^[A-Za-z0-9_./:=@%+,-]+$ ]]; then
            out+=" ${arg}"
        else
            out+=" '${arg//\'/\'\\\'\'}'"
        fi
    done
    printf '%s' "${out# }"
}

# Extract a 0.0.X contract id from yahcli SUCCESS output.
capture_contract_id() {
    local logfile="$1"
    grep -oE 'created contract 0\.[0-9]+\.[0-9]+' "${logfile}" \
        | head -1 | awk '{print $NF}'
}

# Invoke yahcli; tee output to a log; fail on non-zero exit or any "FAILED" line.
yh() {
    local label="$1"; local logfile="$2"; shift 2
    step "${label}"
    printf "${MAGENTA}     \$ ./yahcli %s${RESET}\n" "$(shellquote "$@")"
    set +e
    ./yahcli "$@" 2>&1 | tee "${logfile}"
    local rc=${PIPESTATUS[0]}
    set -e
    if [[ ${rc} -ne 0 ]]; then
        die "${label} — yahcli exited ${rc}"
    fi
    if grep -qE '^\.!\. FAILED|^FAILED ' "${logfile}"; then
        die "${label} — yahcli reported FAILED"
    fi
    ok "${label}"
}

# === Preflight =============================================================
header "Preflight"
[[ -x "${SCRIPT_DIR}/yahcli" ]]     || die "missing ./yahcli launcher"
[[ -f "${SCRIPT_DIR}/yahcli.jar" ]] || die "missing yahcli.jar — run: (cd ${REPO_ROOT} && ./gradlew :yahcli:copyYahCli)"
[[ -f "${PING_PONG_BIN}" ]]         || die "missing PingPong.bin at ${PING_PONG_BIN}"
echo "  JAVA_HOME       : ${JAVA_HOME:-(system default)}"
echo "  NET_A           : ${NET_A}"
echo "  NET_B           : ${NET_B}"
echo "  initial balance : ${INITIAL_BALANCE_TINYBARS} tinybars (~$((INITIAL_BALANCE_TINYBARS / 100000000)) HBAR)"
echo "  create gas      : ${CREATE_GAS}"
echo "  per-step logs   : ${LOG_DIR}/"
ok "All prerequisites present"

# === [1/2] deploy PingPong on alice ========================================
header "[1/2] contracts create — PingPong on ${NET_A}"
yh "contracts create PingPong on ${NET_A}" "${LOG_DIR}/01a-create-pingpong.log" \
    -n "${NET_A}" -p "${PAYER}" contracts create \
        --init-code-file "${PING_PONG_BIN}" \
        --initial-balance "${INITIAL_BALANCE_TINYBARS}" \
        --gas "${CREATE_GAS}" --memo "clpr ping-pong application" --immutable
PINGPONG_A=$(capture_contract_id "${LOG_DIR}/01a-create-pingpong.log")
[[ -n "${PINGPONG_A}" ]] || die "could not capture PingPong contract id on ${NET_A}"

# === [2/2] deploy PingPong on bob ==========================================
header "[2/2] contracts create — PingPong on ${NET_B}"
yh "contracts create PingPong on ${NET_B}" "${LOG_DIR}/01b-create-pingpong.log" \
    -n "${NET_B}" -p "${PAYER}" contracts create \
        --init-code-file "${PING_PONG_BIN}" \
        --initial-balance "${INITIAL_BALANCE_TINYBARS}" \
        --gas "${CREATE_GAS}" --memo "clpr ping-pong application" --immutable
PINGPONG_B=$(capture_contract_id "${LOG_DIR}/01b-create-pingpong.log")
[[ -n "${PINGPONG_B}" ]] || die "could not capture PingPong contract id on ${NET_B}"

header "Done"
cat <<EOF
  PingPong on ${NET_A} : ${PINGPONG_A}
  PingPong on ${NET_B} : ${PINGPONG_B}

  Per-step logs        : ${LOG_DIR}/
EOF
