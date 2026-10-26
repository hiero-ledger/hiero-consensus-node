#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Shared SDK TCK client/server setup, execution and reports for Solo cutover scenarios.
# Callers configure TCK paths/versions and supply run_step, run_command_with_timeout,
# wait_for_tcp_open, deployment_ready and kill_processes_on_local_port.

# pnpm is only needed by the tck server install; require it lazily so runs without
# ENABLE_TCK_TESTS keep working on hosts that never installed it (819 L142-144 parity).
ensure_pnpm_available() {
  if command -v pnpm >/dev/null 2>&1; then
    return 0
  fi
  run_step "Installing pnpm globally (npm install -g pnpm)" npm install -g pnpm || true
  if command -v pnpm >/dev/null 2>&1; then
    return 0
  fi
  echo "pnpm is required for the TCK sdk-server but could not be installed globally." >&2
  echo "Install it manually (e.g. 'npm install -g pnpm' with a writable npm prefix, or corepack enable) and re-run." >&2
  return 1
}

clone_repo_at_tag() {
  local url="$1"
  local dest="$2"
  local tag="$3"
  local clone_cmd=(git clone --depth 1)
  if [[ -n "${tag}" ]]; then
    clone_cmd+=(--branch "${tag}")
  fi
  clone_cmd+=("${url}" "${dest}")
  rm -rf "${dest}" >/dev/null 2>&1 || true
  if ! run_step "Cloning ${url} (${tag:-default branch})" "${clone_cmd[@]}"; then
    echo "Clone failed for ${url} at '${tag:-default branch}' — check that the tag exists and the host has network access" >&2
    return 1
  fi
}

# Resolves TCK_REPO_DIR / JS_SDK_REPO_DIR: reuse the *_REPO_PATH checkouts as-is when given,
# otherwise clone the *_VERSION tags (default: the XTS pins from .citr-env) into WORK_DIR.
prepare_tck_repos() {
  if [[ -n "${TCK_REPO_PATH}" ]]; then
    if [[ ! -f "${TCK_REPO_PATH}/package.json" ]]; then
      echo "TCK_REPO_PATH does not look like a hiero-sdk-tck checkout (missing package.json): ${TCK_REPO_PATH}" >&2
      return 1
    fi
    if [[ -n "${TCK_VERSION}" ]]; then
      echo "NOTE: TCK_REPO_PATH is set; using the checkout as-is (TCK_VERSION=${TCK_VERSION} ignored)"
    fi
    TCK_REPO_DIR="${TCK_REPO_PATH}"
  else
    clone_repo_at_tag "https://github.com/hiero-ledger/hiero-sdk-tck.git" "${TCK_CLONE_DIR}" "${TCK_VERSION}" || return 1
    TCK_REPO_DIR="${TCK_CLONE_DIR}"
  fi

  if [[ -n "${JS_SDK_REPO_PATH}" ]]; then
    if [[ ! -d "${JS_SDK_REPO_PATH}/tck" ]]; then
      echo "JS_SDK_REPO_PATH does not look like a hiero-sdk-js checkout (missing tck/ dir): ${JS_SDK_REPO_PATH}" >&2
      return 1
    fi
    if [[ -n "${JS_SDK_VERSION}" ]]; then
      echo "NOTE: JS_SDK_REPO_PATH is set; using the checkout as-is (JS_SDK_VERSION=${JS_SDK_VERSION} ignored)"
    fi
    echo "NOTE: the tck server setup runs 'pnpm add' inside ${JS_SDK_REPO_PATH}/tck and rewrites its package.json + lockfile"
    JS_SDK_REPO_DIR="${JS_SDK_REPO_PATH}"
  else
    clone_repo_at_tag "https://github.com/hiero-ledger/hiero-sdk-js.git" "${JS_SDK_CLONE_DIR}" "${JS_SDK_VERSION}" || return 1
    if [[ ! -d "${JS_SDK_CLONE_DIR}/tck" ]]; then
      echo "Cloned hiero-sdk-js has no tck/ directory (does tag '${JS_SDK_VERSION:-default branch}' predate the TCK server?)" >&2
      return 1
    fi
    JS_SDK_REPO_DIR="${JS_SDK_CLONE_DIR}"
  fi
  echo "TCK client: ${TCK_REPO_DIR} (${TCK_VERSION:-default branch}); JS-SDK server: ${JS_SDK_REPO_DIR} (${JS_SDK_VERSION:-default branch})"
}

# run_command_with_timeout backgrounds "$@" in this shell, so plain functions are valid
# commands for it — these tiny wrappers exist to give the install phases a timeout without
# quoting gymnastics around cd.
tck_npm_install() { (cd "${TCK_REPO_DIR}" && npm install --no-fund --no-audit); }

install_tck_client_dependencies() {
  run_step "Installing TCK client dependencies (npm install in ${TCK_REPO_DIR})" \
    run_command_with_timeout "${TCK_INSTALL_TIMEOUT_SECS}" tck_npm_install
}

# Versions pinned into the tck server install, extracted from the JS-SDK checkout's own
# package.json files (819 L158-176 parity). Globals so the timeout-wrapped install helper
# can read them.
TCK_SDK_SERVER_SDK_VERSION=""
TCK_SDK_SERVER_LONG_VERSION=""
TCK_SDK_SERVER_PROTO_VERSION=""
TCK_SDK_SERVER_TS_VERSION=""

tck_sdk_server_pnpm_install() {
  (
    cd "${JS_SDK_REPO_DIR}/tck" || exit 1
    # tck/ is not a member of the sdk repo's pnpm workspace, so give it its own config-only
    # workspace file (pnpm >= 10 reads build-script approvals from pnpm-workspace.yaml, not
    # package.json): run dependency build scripts — a plain install fails on pnpm >= 10 with
    # ERR_PNPM_IGNORED_BUILDS otherwise — but never the browser drivers, which are test-only
    # deps of the sdk repo, irrelevant to the tck server, and whose postinstalls break on
    # unpinned transitive drift (chromedriver's install.js dies with ERR_REQUIRE_ESM on node 20).
    printf 'dangerouslyAllowAllBuilds: true\nignoredBuiltDependencies:\n  - chromedriver\n  - geckodriver\n' > pnpm-workspace.yaml
    # tck/package.json pins ts-node but not typescript; resolved standalone that floats to the
    # newest major, which ts-node 10 cannot drive (readConfig fileExists crash on TS 7). Pin the
    # sdk root's own typescript so the server runs against what the sdk repo develops with.
    pnpm add "@hiero-ledger/sdk@^${TCK_SDK_SERVER_SDK_VERSION}" "long@${TCK_SDK_SERVER_LONG_VERSION}" "@hiero-ledger/proto@${TCK_SDK_SERVER_PROTO_VERSION}" \
      && pnpm add -D "typescript@${TCK_SDK_SERVER_TS_VERSION}" \
      && pnpm install
  )
}

start_tck_sdk_server() {
  local tck_dir="${JS_SDK_REPO_DIR}/tck"
  if [[ ! -d "${tck_dir}" ]]; then
    echo "JS-SDK tck server directory not found: ${tck_dir}" >&2
    return 1
  fi

  TCK_SDK_SERVER_SDK_VERSION="$(cd "${tck_dir}" && node -e "console.log(require('../package.json').version)" 2>/dev/null || true)"
  TCK_SDK_SERVER_LONG_VERSION="$(cd "${tck_dir}" && node -e "console.log(require('../package.json').dependencies.long)" 2>/dev/null || true)"
  TCK_SDK_SERVER_PROTO_VERSION="$(cd "${tck_dir}" && node -e "console.log(require('../packages/proto/package.json').version)" 2>/dev/null || true)"
  TCK_SDK_SERVER_TS_VERSION="$(cd "${tck_dir}" && node -e "console.log(require('../package.json').devDependencies.typescript)" 2>/dev/null || true)"
  if [[ -z "${TCK_SDK_SERVER_TS_VERSION}" || "${TCK_SDK_SERVER_TS_VERSION}" == "undefined" ]]; then
    # Known-good with the tck server's ts-node 10 pin; only used if the sdk root drops its own pin.
    TCK_SDK_SERVER_TS_VERSION="5.9.3"
  fi
  if [[ -z "${TCK_SDK_SERVER_SDK_VERSION}" || -z "${TCK_SDK_SERVER_LONG_VERSION}" || "${TCK_SDK_SERVER_LONG_VERSION}" == "undefined" || -z "${TCK_SDK_SERVER_PROTO_VERSION}" ]]; then
    echo "Could not extract sdk/long/proto versions from the JS-SDK checkout at ${JS_SDK_REPO_DIR}" >&2
    echo "  sdk='${TCK_SDK_SERVER_SDK_VERSION}' long='${TCK_SDK_SERVER_LONG_VERSION}' proto='${TCK_SDK_SERVER_PROTO_VERSION}'" >&2
    return 1
  fi

  run_step "Pinning tck server deps (sdk ^${TCK_SDK_SERVER_SDK_VERSION}, long ${TCK_SDK_SERVER_LONG_VERSION}, proto ${TCK_SDK_SERVER_PROTO_VERSION}, typescript ${TCK_SDK_SERVER_TS_VERSION}) + pnpm install" \
    run_command_with_timeout "${TCK_INSTALL_TIMEOUT_SECS}" tck_sdk_server_pnpm_install || return 1

  # 819 starts the server with no args (default port 8544). Pass the port only when overridden;
  # override support depends on the checked-out tck server accepting a port argument.
  local start_arg=""
  if [[ "${TCK_JSON_RPC_PORT}" != "8544" ]]; then
    start_arg="${TCK_JSON_RPC_PORT}"
  fi
  kill_processes_on_local_port "${TCK_JSON_RPC_PORT}"
  echo "Starting TCK JSON-RPC server (pnpm start${start_arg:+ ${start_arg}}) in ${tck_dir} (log: ${TCK_SDK_SERVER_LOG})"
  TCK_SDK_SERVER_PID="$(
    cd "${tck_dir}" || exit 1
    nohup pnpm start ${start_arg:+"${start_arg}"} >"${TCK_SDK_SERVER_LOG}" 2>&1 < /dev/null &
    echo $!
  )"
  if [[ -z "${TCK_SDK_SERVER_PID}" ]]; then
    echo "Failed to launch the TCK JSON-RPC server (no PID captured)" >&2
    return 1
  fi

  if ! wait_for_tcp_open "127.0.0.1" "${TCK_JSON_RPC_PORT}" 60 2 "Waiting for TCK JSON-RPC server on 127.0.0.1:${TCK_JSON_RPC_PORT}"; then
    echo "TCK JSON-RPC server did not open 127.0.0.1:${TCK_JSON_RPC_PORT}" >&2
    if ! kill -0 "${TCK_SDK_SERVER_PID}" >/dev/null 2>&1; then
      echo "  server process ${TCK_SDK_SERVER_PID} already exited" >&2
    fi
    if [[ -s "${TCK_SDK_SERVER_LOG}" ]]; then
      echo "  last server log lines:" >&2
      tail -n 30 "${TCK_SDK_SERVER_LOG}" | sed 's/^/    /' >&2
    fi
    return 1
  fi
  echo "TCK JSON-RPC server ready on http://127.0.0.1:${TCK_JSON_RPC_PORT} (pid ${TCK_SDK_SERVER_PID})"
}

# Bounded rollout wait for a single mirror deployment (wait_for_required_mirror_services_ready
# covers the required set; TCK additionally needs restjava, which that set tolerates missing).
wait_for_mirror_deployment_ready() {
  local deployment="$1"
  local timeout_secs="$2"
  local start_ts
  start_ts="$(date +%s)"
  while true; do
    if deployment_ready "${deployment}" 5; then
      return 0
    fi
    if (( $(date +%s) - start_ts >= timeout_secs )); then
      return 1
    fi
    sleep 5
  done
}

# Establishes the three forwards only TCK needs (CN gRPC + mirror REST are owned by
# restart_post_upgrade_port_forwards). Step-scoped: not watchdog-managed, killed
# unconditionally by cleanup().
ensure_tck_port_forwards() {
  # restjava is the one mirror deployment the deploy path tolerates being down (see
  # mirror_node_failed_only_on_restjava); TCK is the only consumer that REQUIRES it.
  echo "Waiting for mirror-1-restjava rollout (TCK needs MIRROR_NODE_REST_JAVA_URL; up to ${MIRROR_RESTJAVA_READY_TIMEOUT_SECS}s)"
  if ! wait_for_mirror_deployment_ready mirror-1-restjava "${MIRROR_RESTJAVA_READY_TIMEOUT_SECS}"; then
    echo "mirror-1-restjava is not ready — the TCK suite requires the restjava API." >&2
    echo "The mirror deploy tolerates restjava being down, so the network can look healthy otherwise." >&2
    echo "Inspect: kubectl -n ${SOLO_NAMESPACE} get deploy mirror-1-restjava (OOMKills are the usual cause);" >&2
    echo "consider raising MIRROR_RESTJAVA_MEMORY_LIMIT (currently ${MIRROR_RESTJAVA_MEMORY_LIMIT})." >&2
    return 1
  fi
  if ! wait_for_mirror_deployment_ready mirror-1-grpc 120; then
    echo "mirror-1-grpc deployment is not ready; cannot forward MIRROR_NETWORK for TCK" >&2
    return 1
  fi
  if ! wait_for_mirror_deployment_ready mirror-1-web3 120; then
    echo "mirror-1-web3 is not ready; TCK contract queries require Web3 for gas estimation" >&2
    return 1
  fi

  local grpc_log="${WORK_DIR}/port-forward-mirror-grpc.log"
  local restjava_log="${WORK_DIR}/port-forward-mirror-restjava.log"
  local web3_log="${WORK_DIR}/port-forward-mirror-web3.log"

  if wait_for_tcp_open "127.0.0.1" "${MIRROR_GRPC_LOCAL_PORT}" 1 1; then
    echo "Mirror gRPC forward already active on localhost:${MIRROR_GRPC_LOCAL_PORT}"
  else
    kill_processes_on_local_port "${MIRROR_GRPC_LOCAL_PORT}"
    echo "Opening mirror gRPC port-forward svc/mirror-1-grpc ${MIRROR_GRPC_LOCAL_PORT}:5600 (kubectl log: ${grpc_log})"
    nohup kubectl -n "${SOLO_NAMESPACE}" port-forward svc/mirror-1-grpc "${MIRROR_GRPC_LOCAL_PORT}:5600" >"${grpc_log}" 2>&1 < /dev/null &
    MIRROR_GRPC_PORT_FORWARD_PID="$!"
    disown "${MIRROR_GRPC_PORT_FORWARD_PID}" 2>/dev/null || true
  fi

  if wait_for_tcp_open "127.0.0.1" "${MIRROR_RESTJAVA_LOCAL_PORT}" 1 1; then
    echo "Mirror restjava forward already active on localhost:${MIRROR_RESTJAVA_LOCAL_PORT}"
  else
    kill_processes_on_local_port "${MIRROR_RESTJAVA_LOCAL_PORT}"
    echo "Opening mirror restjava port-forward svc/mirror-1-restjava ${MIRROR_RESTJAVA_LOCAL_PORT}:http (kubectl log: ${restjava_log})"
    nohup kubectl -n "${SOLO_NAMESPACE}" port-forward svc/mirror-1-restjava "${MIRROR_RESTJAVA_LOCAL_PORT}:http" >"${restjava_log}" 2>&1 < /dev/null &
    MIRROR_RESTJAVA_PORT_FORWARD_PID="$!"
    disown "${MIRROR_RESTJAVA_PORT_FORWARD_PID}" 2>/dev/null || true
  fi

  # JS SDK localMirrorRestBaseUrl maps local contract-call requests to port 8545.
  # This port must match the SDK even when the TCK's other local ports are overridden.
  if wait_for_tcp_open "127.0.0.1" 8545 1 1; then
    echo "Mirror Web3 forward already active on localhost:8545"
  else
    kill_processes_on_local_port 8545
    echo "Opening mirror Web3 port-forward svc/mirror-1-web3 8545:http (kubectl log: ${web3_log})"
    nohup kubectl -n "${SOLO_NAMESPACE}" port-forward svc/mirror-1-web3 8545:http >"${web3_log}" 2>&1 < /dev/null &
    MIRROR_WEB3_PORT_FORWARD_PID="$!"
    disown "${MIRROR_WEB3_PORT_FORWARD_PID}" 2>/dev/null || true
  fi

  if ! wait_for_tcp_open "127.0.0.1" "${MIRROR_GRPC_LOCAL_PORT}" 20 1 "Waiting for mirror gRPC forward on 127.0.0.1:${MIRROR_GRPC_LOCAL_PORT}"; then
    echo "Mirror gRPC port-forward did not become reachable on localhost:${MIRROR_GRPC_LOCAL_PORT}" >&2
    if [[ -s "${grpc_log}" ]]; then
      tail -n 20 "${grpc_log}" >&2
    fi
    return 1
  fi
  if ! wait_for_tcp_open "127.0.0.1" "${MIRROR_RESTJAVA_LOCAL_PORT}" 20 1 "Waiting for mirror restjava forward on 127.0.0.1:${MIRROR_RESTJAVA_LOCAL_PORT}"; then
    echo "Mirror restjava port-forward did not become reachable on localhost:${MIRROR_RESTJAVA_LOCAL_PORT}" >&2
    if [[ -s "${restjava_log}" ]]; then
      tail -n 20 "${restjava_log}" >&2
    fi
    return 1
  fi
  if ! wait_for_tcp_open "127.0.0.1" 8545 20 1 "Waiting for mirror Web3 forward on 127.0.0.1:8545"; then
    echo "Mirror Web3 port-forward did not become reachable on localhost:8545" >&2
    if [[ -s "${web3_log}" ]]; then
      tail -n 20 "${web3_log}" >&2
    fi
    return 1
  fi
}

# CI parity (819 L202-207): create a fresh 1,000,000-hbar account carrying the well-known dev
# key before the suite. Repeat-safe (a new account per invocation) and doubles as a solo-CLI
# level liveness check of the post-cutover network. No solo-version gate: this script already
# depends on the new command tree throughout.
fund_tck_operator_account() {
  run_step "Funding TCK operator account via solo ledger account create (1,000,000 hbar, dev key)" \
    solo ledger account create --dev --ed25519-private-key "${OPERATOR_PRIVATE_KEY}" \
      --deployment "${SOLO_DEPLOYMENT}" --hbar-amount 1000000
}

# Single place the TCK client env/cwd is defined — the 819 L191-200 values parameterised on
# this script's local forwards. Process env wins over the repo's .env (dotenv never overrides
# existing vars), matching CI. Subshell so the cd cannot leak. NODE_ACCOUNT_ID stays 0.0.3
# because the CN forward always targets haproxy-node1-svc (node1).
run_tck_npm() {
  (
    cd "${TCK_REPO_DIR}" || exit 1
    # Parallel mocha workers must serialize full suites for Mochawesome; otherwise
    # the report contains totals but no test names, assertions or stack traces.
    if [[ -f node_modules/mochawesome/register.js ]]; then
      export NODE_PATH="${TCK_REPO_DIR}/node_modules${NODE_PATH:+:${NODE_PATH}}"
      export NODE_OPTIONS="${NODE_OPTIONS:-} --require mochawesome/register"
    fi
    mkdir -p "${TCK_REPORT_EXPORT_DIR}"
    set -o pipefail
    OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID}" \
    OPERATOR_ACCOUNT_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY}" \
    JSON_RPC_SERVER_URL="http://127.0.0.1:${TCK_JSON_RPC_PORT}" \
    NODE_IP="127.0.0.1:${CN_GRPC_LOCAL_PORT}" \
    NODE_ACCOUNT_ID="0.0.3" \
    NODE_TIMEOUT="30000" \
    MIRROR_NODE_REST_URL="http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}" \
    MIRROR_NODE_REST_JAVA_URL="http://127.0.0.1:${MIRROR_RESTJAVA_LOCAL_PORT}" \
    MIRROR_NETWORK="127.0.0.1:${MIRROR_GRPC_LOCAL_PORT}" \
    npm run "$@" 2>&1 | tee -a "${TCK_REPORT_EXPORT_DIR}/tck-console.log"
  )
}

# Terminate the npm/mocha/worker descendants too: stopping only the wrapper can
# leave workers running and submitting transactions after an infrastructure failure.
stop_tck_process_tree() {
  local parent="$1" child
  while IFS= read -r child; do
    [[ -z "${child}" ]] || stop_tck_process_tree "${child}"
  done < <(pgrep -P "${parent}" 2>/dev/null || true)
  kill -TERM "${parent}" >/dev/null 2>&1 || true
}

# A nodemon launcher remains alive when its SDK server crashes. Check the actual
# listeners while tests run, so a dead server/tunnel produces one infrastructure
# failure instead of hundreds of misleading assertion and connection failures.
run_tck_with_health_checks() (
  local test_pid="" rc=0 endpoint port
  trap '[[ -z "${test_pid}" ]] || stop_tck_process_tree "${test_pid}"' EXIT
  trap 'exit 143' TERM
  trap 'exit 130' INT
  run_tck_npm "$@" &
  test_pid=$!
  while kill -0 "${test_pid}" >/dev/null 2>&1; do
    for endpoint in "SDK JSON-RPC:${TCK_JSON_RPC_PORT}" "CN gRPC:${CN_GRPC_LOCAL_PORT}" \
        "mirror REST:${MIRROR_REST_LOCAL_PORT}" "mirror REST Java:${MIRROR_RESTJAVA_LOCAL_PORT}" \
        "mirror gRPC:${MIRROR_GRPC_LOCAL_PORT}" "mirror Web3:8545"; do
      port="${endpoint##*:}"
      if ! wait_for_tcp_open 127.0.0.1 "${port}" 1 0; then
        mkdir -p "${TCK_REPORT_EXPORT_DIR}"
        echo "SDK TCK INFRASTRUCTURE FAILURE: ${endpoint%:*} listener closed on localhost:${port}; stopping tests. Inspect tck-sdk-server.log and port-forward logs." \
          | tee -a "${TCK_REPORT_EXPORT_DIR}/tck-infrastructure.log" >&2
        return 125
      fi
    done
    sleep 2
  done
  wait "${test_pid}" || rc=$?
  test_pid=""
  return "${rc}"
)

run_tck_tests() {
  mkdir -p "${TCK_REPORT_EXPORT_DIR}"
  : > "${TCK_REPORT_EXPORT_DIR}/tck-console.log"
  rm -f "${TCK_REPORT_EXPORT_DIR}/tck-infrastructure.log"
  if [[ -f "${TCK_REPO_DIR}/.env.custom_node" ]]; then
    cp "${TCK_REPO_DIR}/.env.custom_node" "${TCK_REPO_DIR}/.env"
  else
    echo "WARNING: ${TCK_REPO_DIR}/.env.custom_node not found (older TCK tag?); writing an empty .env — all required config is passed as process env" >&2
    : > "${TCK_REPO_DIR}/.env"
  fi

  if [[ -n "${TCK_TEST_FILES}" ]]; then
    local files_normalized="${TCK_TEST_FILES//,/ }"
    local f file_rc
    # shellcheck disable=SC2086 # word splitting of the file list is intended
    for f in ${files_normalized}; do
      file_rc=0
      run_step "TCK test file ${f} (timeout ${TCK_TEST_TIMEOUT_SECS}s)" \
        run_command_with_timeout "${TCK_TEST_TIMEOUT_SECS}" run_tck_with_health_checks test:file "${f}" || file_rc=$?
      # mochawesome overwrites its report every run — export per file, keep the failed file's report too.
      export_tck_report "$(basename "${f%.*}")" || true
      if [[ "${file_rc}" -ne 0 ]]; then
        return "${file_rc}"
      fi
    done
    return 0
  fi

  run_step "Running full TCK suite (npm run ${TCK_TEST_SCRIPT}, timeout ${TCK_TEST_TIMEOUT_SECS}s)" \
    run_command_with_timeout "${TCK_TEST_TIMEOUT_SECS}" run_tck_with_health_checks "${TCK_TEST_SCRIPT}"
}

# Copies reports, browser assets and run metadata out of the TCK
# repo into GENERATED_DIR so it survives WORK_DIR removal; suffix disambiguates per-file runs.
export_tck_report() {
  local suffix="${1:-}"
  local report_dir="${TCK_REPO_DIR}/mochawesome-report"
  if [[ ! -d "${report_dir}" ]]; then
    echo "WARNING: no mochawesome report directory at ${report_dir} (test runner crashed before reporting?)" >&2
    return 1
  fi
  mkdir -p "${TCK_REPORT_EXPORT_DIR}"
  local src base dest exported=0
  for src in "${report_dir}"/*; do
    [[ -e "${src}" ]] || continue
    base="$(basename "${src}")"
    if [[ -n "${suffix}" && -f "${src}" && "${base}" == *.* ]]; then
      dest="${TCK_REPORT_EXPORT_DIR}/${base%.*}-${suffix}.${base##*.}"
    else
      dest="${TCK_REPORT_EXPORT_DIR}/${base}"
    fi
    if [[ -d "${src}" ]]; then
      mkdir -p "${dest}"
      cp -R "${src}/." "${dest}/"
    else
      cp "${src}" "${dest}"
    fi
    exported=1
  done
  if (( exported == 0 )); then
    echo "WARNING: no mochawesome report files found under ${report_dir}" >&2
    return 1
  fi
  echo "TCK report exported to ${TCK_REPORT_EXPORT_DIR}"
}

# Structured recap after the run: per-report stats plus every failed test with its reason.
# (Live per-test spec output already streamed to the console while the suite ran.)
print_tck_report_summary() {
  local json failures stats found=0 report_count=0
  local total_tests=0 total_passes=0 total_failures=0 total_pending=0 total_skipped=0 total_duration_ms=0
  TCK_STATS_SUMMARY=""
  echo "--- TCK report summary ---"
  for json in "${TCK_REPORT_EXPORT_DIR}"/mochawesome*.json; do
    [[ -e "${json}" ]] || continue
    found=1
    stats="$(jq -r '.stats | [(.tests // 0), (.passes // 0), (.failures // 0), (.pending // 0), (.skipped // 0), (.duration // 0)] | @tsv' "${json}" 2>/dev/null || true)"
    if [[ "${stats}" =~ ^[0-9]+$'\t'[0-9]+$'\t'[0-9]+$'\t'[0-9]+$'\t'[0-9]+$'\t'[0-9]+$ ]]; then
      local tests passes failed pending skipped duration_ms
      IFS=$'\t' read -r tests passes failed pending skipped duration_ms <<< "${stats}"
      echo "$(basename "${json}"): tests=${tests} passes=${passes} failures=${failed} pending=${pending} skipped=${skipped} duration=${duration_ms}ms"
      total_tests=$((total_tests + tests))
      total_passes=$((total_passes + passes))
      total_failures=$((total_failures + failed))
      total_pending=$((total_pending + pending))
      total_skipped=$((total_skipped + skipped))
      total_duration_ms=$((total_duration_ms + duration_ms))
      report_count=$((report_count + 1))
    else
      echo "$(basename "${json}"): unparseable stats"
    fi
    # mochawesome nests suites arbitrarily deep — recursive descent finds every failed test.
    failures="$(jq -r '[.. | objects | select(.state? == "failed")] | .[] | "  ✗ \(.fullTitle // .title // "unnamed test") — \((.err.message // "no message") | split("\n")[0])"' "${json}" 2>/dev/null || true)"
    if [[ -n "${failures}" ]]; then
      printf '%s\n' "${failures}"
    fi
  done
  if (( found == 0 )); then
    echo "WARNING: no exported mochawesome JSON under ${TCK_REPORT_EXPORT_DIR} (test runner may have crashed before reporting)" >&2
    return 0
  fi
  if (( report_count > 0 )); then
    TCK_STATS_SUMMARY="tests=${total_tests} passes=${total_passes} failures=${total_failures} pending=${total_pending} skipped=${total_skipped} duration=$(((total_duration_ms + 999) / 1000))s (${report_count} report(s))"
  fi
  echo "Full browsable report(s): ${TCK_REPORT_EXPORT_DIR} (mochawesome*.html)"
}

cleanup_tck_resources() {
  # Preserve the sdk-server log next to the exported TCK report before killing the server:
  # WORK_DIR is a temp dir that never reaches CI artifacts, and the log carries the per-request
  # receipts needed to tell "no receipt from CN" apart from "mirror never showed the update".
  if [[ -s "${TCK_SDK_SERVER_LOG}" ]]; then
    mkdir -p "${TCK_REPORT_EXPORT_DIR}" >/dev/null 2>&1 || true
    cp "${TCK_SDK_SERVER_LOG}" "${TCK_REPORT_EXPORT_DIR}/tck-sdk-server.log" >/dev/null 2>&1 || true
  fi
  local forward_log
  for forward_log in "${WORK_DIR:-}/port-forward-"*.log; do
    [[ -f "${forward_log}" ]] || continue
    mkdir -p "${TCK_REPORT_EXPORT_DIR}"
    cp "${forward_log}" "${TCK_REPORT_EXPORT_DIR}/" >/dev/null 2>&1 || true
  done

  # TCK helpers are host-local only — the JSON-RPC sdk-server and the mirror
  # grpc/restjava/Web3 forwards exist solely for the TCK client. Kill them regardless of exit
  # code / KEEP_NETWORK; pnpm spawns nodemon which spawns the node that owns the socket,
  # so TERM the child tree first, then the pid, then sweep the port for the grandchild.
  if [[ -n "${TCK_SDK_SERVER_PID}" ]]; then
    pkill -TERM -P "${TCK_SDK_SERVER_PID}" >/dev/null 2>&1 || true
    kill "${TCK_SDK_SERVER_PID}" >/dev/null 2>&1 || true
    kill_processes_on_local_port "${TCK_JSON_RPC_PORT}"
  fi
  if [[ -n "${MIRROR_GRPC_PORT_FORWARD_PID}" ]]; then
    kill "${MIRROR_GRPC_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  if [[ -n "${MIRROR_RESTJAVA_PORT_FORWARD_PID}" ]]; then
    kill "${MIRROR_RESTJAVA_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  if [[ -n "${MIRROR_WEB3_PORT_FORWARD_PID:-}" ]]; then
    kill "${MIRROR_WEB3_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi

  if [[ -n "${TCK_CN_PORT_FORWARD_PID:-}" ]]; then
    kill "${TCK_CN_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  TCK_SDK_SERVER_PID=""
  MIRROR_GRPC_PORT_FORWARD_PID=""
  MIRROR_RESTJAVA_PORT_FORWARD_PID=""
  MIRROR_WEB3_PORT_FORWARD_PID=""
  TCK_CN_PORT_FORWARD_PID=""
}
