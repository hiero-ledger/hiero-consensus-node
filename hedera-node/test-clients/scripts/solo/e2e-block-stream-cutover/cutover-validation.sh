#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Ingestion and continuity assertions for the three-stage Solo cutover.
# Callers supply runtime paths, MinIO discovery helpers, transaction nudges and port forwards.

verify_docker_memory_budget() {
  local bytes available_mib
  bytes="$(docker info --format '{{.MemTotal}}')" || return 1
  [[ "${bytes}" =~ ^[0-9]+$ ]] || return 1
  available_mib=$((bytes / 1024 / 1024))
  log "Docker memory available to Kind: ${available_mib} MiB (required >= ${DOCKER_MIN_MEMORY_MIB} MiB)"
  if (( available_mib < DOCKER_MIN_MEMORY_MIB )); then
    echo "Docker has too little memory for the four-CN cutover with BN and MN. Allocate at least 32 GiB in Docker Desktop, restart Docker, and rerun. Current cluster has been left unchanged." >&2
    return 1
  fi
}

MINIO_COUNT_POD=""
MINIO_COUNT_ENDPOINT=""
MINIO_COUNT_USER=""
MINIO_COUNT_PASS=""

resolve_minio_count_context() {
  if [[ -n "${MINIO_COUNT_POD}" ]]; then
    return 0
  fi
  local pod svc svc_port creds_file creds_tmp cfg_full server_url
  local endpoint_try u p list_ok=0

  pod="$(kubectl -n "${MINIO_NAMESPACE}" get pods -o json 2>/dev/null | jq -r '
    .items[].metadata.name
    | select(test("^minio-"))
  ' | head -n 1)"
  if [[ -z "${pod}" ]]; then
    echo "resolve_minio_count_context: could not find MinIO pod in namespace ${MINIO_NAMESPACE}" >&2
    return 1
  fi

  creds_file="$(mktemp)"
  creds_tmp="$(mktemp)"
  if minio_discover_pod_credentials "${MINIO_NAMESPACE}" >"${creds_tmp}"; then
    paste -sd '\t' "${creds_tmp}" >>"${creds_file}"
  fi
  : >"${creds_tmp}"
  if minio_discover_secret_env_credentials "${MINIO_NAMESPACE}" "minio-secrets" >"${creds_tmp}"; then
    paste -sd '\t' "${creds_tmp}" >>"${creds_file}"
  fi
  : >"${creds_tmp}"
  if minio_discover_secret_env_credentials "${MINIO_NAMESPACE}" "myminio-env-configuration" >"${creds_tmp}"; then
    paste -sd '\t' "${creds_tmp}" >>"${creds_file}"
  fi
  rm -f "${creds_tmp}"
  if [[ ! -s "${creds_file}" ]]; then
    rm -f "${creds_file}"
    echo "resolve_minio_count_context: could not discover MinIO credentials in namespace ${MINIO_NAMESPACE}" >&2
    return 1
  fi

  cfg_full="$(kubectl -n "${MINIO_NAMESPACE}" exec "${pod}" -c minio -- sh -c \
    "cat \"\${MINIO_CONFIG_ENV_FILE:-/tmp/minio/config.env}\" 2>/dev/null || true" 2>/dev/null || true)"
  server_url="$(echo "${cfg_full}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_SERVER_URL=//p' | head -1 | tr -d '"\r')"
  svc="$(minio_discover_service "${MINIO_NAMESPACE}" 2>/dev/null || true)"
  svc_port=""
  if [[ -n "${svc}" ]]; then
    svc_port="$(minio_discover_service_port "${MINIO_NAMESPACE}" "${svc}" 2>/dev/null || true)"
  fi
  local svc_endpoint=""
  if [[ -n "${svc}" && -n "${svc_port}" ]]; then
    svc_endpoint="http://${svc}.${MINIO_NAMESPACE}.svc.cluster.local:${svc_port}"
  fi

  # Probe endpoints x credentials with a cheap bucket listing until one works,
  # then pin the combination. Retries ride out transient DNS/service hiccups.
  for _ in 1 2 3; do
    for endpoint_try in \
      "${server_url}" \
      "${svc_endpoint}" \
      "http://minio-hl.${MINIO_NAMESPACE}.svc.cluster.local:9000"; do
      [[ -n "${endpoint_try}" ]] || continue
      while IFS=$'\t' read -r u p; do
        [[ -n "${u}" && -n "${p}" ]] || continue
        if kubectl -n "${MINIO_NAMESPACE}" exec "${pod}" -c minio -- sh -c \
          'mc alias set local "$1" "$2" "$3" >/dev/null 2>&1 && mc ls "local/$4" >/dev/null 2>&1' \
          sh "${endpoint_try}" "${u}" "${p}" "${MINIO_BUCKET}"; then
          MINIO_COUNT_POD="${pod}"
          MINIO_COUNT_ENDPOINT="${endpoint_try}"
          MINIO_COUNT_USER="${u}"
          MINIO_COUNT_PASS="${p}"
          list_ok=1
          break
        fi
      done < "${creds_file}"
      (( list_ok == 1 )) && break
    done
    (( list_ok == 1 )) && break
    sleep 2
  done
  rm -f "${creds_file}"
  if (( list_ok == 0 )); then
    echo "resolve_minio_count_context: no MinIO endpoint/credential combination could list bucket ${MINIO_BUCKET}" >&2
    return 1
  fi
  # stderr: callers of count_minio_record_stream_objects capture stdout as the count.
  echo "  pinned MinIO count context: pod=${MINIO_COUNT_POD} endpoint=${MINIO_COUNT_ENDPOINT}" >&2
}

# Print the number of record-stream objects (*.rcd, *.rcd.gz, *.rcd_sig,
# sidecars) under the recordstreams prefix. Listing failures return non-zero —
# never a silent 0 — so "count stopped growing" cannot be faked by a broken
# listing. The find output is counted host-side because the in-pod sh has no
# pipefail and `mc find | wc -l` would swallow an mc error as 0.
count_minio_record_stream_objects() {
  resolve_minio_count_context || return 1
  local listing
  if ! listing="$(kubectl -n "${MINIO_NAMESPACE}" exec "${MINIO_COUNT_POD}" -c minio -- sh -c \
    'mc alias set local "$1" "$2" "$3" >/dev/null 2>&1 && mc find "local/$4/recordstreams" --name "*.rcd*"' \
    sh "${MINIO_COUNT_ENDPOINT}" "${MINIO_COUNT_USER}" "${MINIO_COUNT_PASS}" "${MINIO_BUCKET}" 2>/dev/null)"; then
    return 1
  fi
  printf '%s' "${listing}" | grep -c . || true
}

# One count with a few retries around transient exec/listing failures.
minio_record_object_count_with_retries() {
  local count
  for _ in 1 2 3; do
    if count="$(count_minio_record_stream_objects)" && [[ "${count}" =~ ^[0-9]+$ ]]; then
      echo "${count}"
      return 0
    fi
    sleep 5
  done
  echo "minio_record_object_count_with_retries: could not obtain a record-object count after 3 attempts" >&2
  return 1
}

# After the BLOCKS-only cutover no new record
# objects may appear in MinIO. Pre-cutover phases legitimately filled the
# bucket, so the assert is stability, not emptiness: two samples
# RECORD_CESSATION_SETTLE_SECS apart must match (and be > 0 — zero would mean
# we are counting the wrong bucket/prefix). The stable value is kept in
# RECORD_OBJECT_COUNT_BASELINE for the end-of-step re-check.
RECORD_OBJECT_COUNT_BASELINE=""

verify_record_uploads_ceased_baseline() {
  local a b
  a="$(minio_record_object_count_with_retries)" || return 1
  echo "  record-object count sample 1: ${a}; re-sampling in ${RECORD_CESSATION_SETTLE_SECS}s"
  sleep "${RECORD_CESSATION_SETTLE_SECS}"
  b="$(minio_record_object_count_with_retries)" || return 1
  echo "  record-object count sample 2: ${b}"
  if [[ "${a}" != "${b}" ]]; then
    echo "Record-stream objects are STILL being uploaded to MinIO after cutover (count ${a} -> ${b} across ${RECORD_CESSATION_SETTLE_SECS}s)" >&2
    return 1
  fi
  if (( a == 0 )); then
    echo "Unexpected: zero record-stream objects under ${MINIO_BUCKET}/recordstreams — pre-cutover phases should have uploaded records (wrong bucket/prefix?)" >&2
    return 1
  fi
  RECORD_OBJECT_COUNT_BASELINE="${a}"
  echo "Record-object count stable at ${RECORD_OBJECT_COUNT_BASELINE} post-cutover (pre-cutover records are expected to remain)"
}

# Gap check (final validation, part 2): re-count after the post-cutover validation
# traffic (cryptoCreate + block watch + txId sweep). Any growth means a
# record-file writer/uploader survived the BLOCKS-only cutover.
verify_record_uploads_ceased_final() {
  local final
  final="$(minio_record_object_count_with_retries)" || return 1
  if [[ "${final}" != "${RECORD_OBJECT_COUNT_BASELINE}" ]]; then
    echo "Record-stream object count grew ${RECORD_OBJECT_COUNT_BASELINE} -> ${final} across the post-cutover validation window — record-file uploads did NOT cease" >&2
    return 1
  fi
  echo "Record-file upload cessation confirmed: count ${final} unchanged across the post-cutover validation window"
  VALIDATION_SUMMARY_LINES+=("✓ Record-file upload cessation: MinIO record-object count stable at ${final} (${RECORD_CESSATION_SETTLE_SECS}s settle check + unchanged across the post-cutover validation traffic)")
}


BN_CHECK_PORT_FORWARD_PID=""

start_bn_port_forward() {
  local svc="block-node-${BLOCK_NODE_ID}"
  local remote_port="${BLOCK_NODE_GRPC_PORT:-40840}"
  local local_port="${BLOCK_NODE_GRPC_LOCAL_PORT:-40840}"
  local pf_log="${WORK_DIR}/port-forward-bn-check.log"
  kill_processes_on_local_port "${local_port}"
  : > "${pf_log}"
  nohup kubectl -n "${SOLO_NAMESPACE}" port-forward "svc/${svc}" "${local_port}:${remote_port}" >"${pf_log}" 2>&1 < /dev/null &
  BN_CHECK_PORT_FORWARD_PID=$!
  disown "${BN_CHECK_PORT_FORWARD_PID}" 2>/dev/null || true
  if ! wait_for_tcp_open "127.0.0.1" "${local_port}" 20 1 \
        "Waiting for BN gRPC port-forward on 127.0.0.1:${local_port}"; then
    echo "start_bn_port_forward: could not establish port-forward to ${svc} (last 20 lines of ${pf_log}):" >&2
    tail -n 20 "${pf_log}" >&2 2>/dev/null || true
    stop_bn_port_forward
    return 1
  fi
}

stop_bn_port_forward() {
  if [[ -n "${BN_CHECK_PORT_FORWARD_PID}" ]]; then
    kill "${BN_CHECK_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    BN_CHECK_PORT_FORWARD_PID=""
  fi
}

# grpcurl against the port-forwarded BN. The BN has no gRPC reflection, so each
# call needs the proto + import paths from the block-node checkout. -max-msg-sz
# is raised because a single getBlock response can carry several MB.
bn_grpc_call() {
  local proto_file="$1" method="$2" request_json="$3"
  local local_port="${BLOCK_NODE_GRPC_LOCAL_PORT:-40840}"
  grpcurl -plaintext -max-time 30 -max-msg-sz 67108864 \
    -import-path "${BLOCK_NODE_REPO_PATH}/protobuf-sources/src/main/proto" \
    -import-path "${REPO_ROOT}/hapi/hedera-protobuf-java-api/src/main/proto" \
    -proto "${proto_file}" \
    -d "${request_json}" \
    "127.0.0.1:${local_port}" \
    "${method}"
}

# Post-cutover combined check: (a) the mirror node's top block number keeps
# increasing — polled from /api/v1/blocks?limit=1&order=desc, hard-failing on
# any regression and requiring a cumulative advance of
# MN_BLOCK_PROGRESS_MIN_DELTA within MN_BLOCK_PROGRESS_TIMEOUT_SECS; then
# (b) the Block Node serves every block of the observed span via
# BlockAccessService/getBlock with status SUCCESS and a non-empty item list.
# In BLOCKS mode the CN closes blocks on its block-period timer, so the top
# advances even without fresh transactions (and the final validation cryptoCreate just
# provided some anyway).
verify_mn_blocks_advancing_and_bn_serves() {
  local blocks_url="http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/blocks?limit=1&order=desc"
  local proto_api_root="${BLOCK_NODE_REPO_PATH}/protobuf-sources/src/main/proto"
  if [[ ! -f "${proto_api_root}/block-node/api/block_access_service.proto" ]]; then
    echo "verify_mn_blocks_advancing_and_bn_serves: block_access_service.proto not found under ${proto_api_root}" >&2
    echo "  Ensure BLOCK_NODE_REPO_PATH points at a hiero-block-node checkout." >&2
    return 1
  fi

  local deadline=$((SECONDS + MN_BLOCK_PROGRESS_TIMEOUT_SECS))
  local first_top="" prev_top="" cur_top="" sample=""
  local bad_samples=0 samples=0
  echo "Watching MN top block for a cumulative advance of >= ${MN_BLOCK_PROGRESS_MIN_DELTA} (poll ${MN_BLOCK_PROGRESS_POLL_SECS}s, up to ${MN_BLOCK_PROGRESS_TIMEOUT_SECS}s)"
  while (( SECONDS < deadline )); do
    sample="$(curl -sf --connect-timeout 5 --max-time 15 "${blocks_url}" 2>/dev/null | jq -r '.blocks[0].number // empty' 2>/dev/null)" || true
    if [[ ! "${sample}" =~ ^[0-9]+$ ]]; then
      # A curl/jq blip is not a regression; only give up after several in a row.
      bad_samples=$((bad_samples + 1))
      if (( bad_samples >= 6 )); then
        echo "verify_mn_blocks_advancing_and_bn_serves: ${bad_samples} consecutive unusable samples from ${blocks_url} (last: '${sample:-<empty>}')" >&2
        return 1
      fi
      sleep "${MN_BLOCK_PROGRESS_POLL_SECS}"
      continue
    fi
    bad_samples=0
    samples=$((samples + 1))
    cur_top="${sample}"
    if [[ -z "${first_top}" ]]; then
      first_top="${cur_top}"
      echo "  initial MN top block: ${first_top}"
    fi
    if [[ -n "${prev_top}" ]] && (( cur_top < prev_top )); then
      echo "verify_mn_blocks_advancing_and_bn_serves: MN top block REGRESSED ${prev_top} -> ${cur_top}" >&2
      return 1
    fi
    if [[ -n "${prev_top}" && "${cur_top}" != "${prev_top}" ]]; then
      echo "  MN top block: ${cur_top} (+$((cur_top - first_top)) since watch start)"
    fi
    prev_top="${cur_top}"
    if (( cur_top - first_top >= MN_BLOCK_PROGRESS_MIN_DELTA )); then
      break
    fi
    sleep "${MN_BLOCK_PROGRESS_POLL_SECS}"
  done
  if [[ -z "${first_top}" ]] || (( cur_top - first_top < MN_BLOCK_PROGRESS_MIN_DELTA )); then
    echo "verify_mn_blocks_advancing_and_bn_serves: MN top block advanced only $(( ${cur_top:-0} - ${first_top:-0} )) (< ${MN_BLOCK_PROGRESS_MIN_DELTA}) within ${MN_BLOCK_PROGRESS_TIMEOUT_SECS}s (first=${first_top:-none}, last=${cur_top:-none}) — mirror ingestion from the BN appears stalled" >&2
    return 1
  fi
  echo "MN top block advanced ${first_top} -> ${cur_top} (+$((cur_top - first_top))) across ${samples} samples with no regression"
  VALIDATION_SUMMARY_LINES+=("✓ MN block progress: top block advanced ${first_top} -> ${cur_top} (+$((cur_top - first_top))) across ${samples} samples with no regression")

  # (b) Require the observed span (first_top, cur_top] to be available from BN,
  # then fetch up to BN_GET_BLOCK_MAX_PER_CHECK of its newest blocks.
  start_bn_port_forward || return 1
  local sweep_rc=0
  local span_first=$((first_top + 1)) span_last="${cur_top}"
  local status_json bn_first bn_last
  status_json="$(bn_grpc_call "block-node/api/node_service.proto" \
    org.hiero.block.api.BlockNodeService/serverStatus '{}' 2>/dev/null)" || true
  # uint64s arrive as JSON strings; zero-valued fields are omitted entirely.
  bn_first="$(echo "${status_json}" | jq -r '.firstAvailableBlock // empty' 2>/dev/null)" || true
  bn_last="$(echo "${status_json}" | jq -r '.lastAvailableBlock // empty' 2>/dev/null)" || true
  [[ "${bn_first}" =~ ^[0-9]+$ ]] || bn_first=0
  if [[ ! "${bn_last}" =~ ^[0-9]+$ ]]; then
    echo "verify_mn_blocks_advancing_and_bn_serves: BN serverStatus did not return a numeric lastAvailableBlock (response: ${status_json:-<empty>})" >&2
    sweep_rc=1
  else
    local get_first="${span_first}" get_last="${span_last}"
    if (( get_first < bn_first || get_last > bn_last )); then
      echo "verify_mn_blocks_advancing_and_bn_serves: observed MN span ${span_first}..${span_last} is outside BN available range ${bn_first}..${bn_last}" >&2
      sweep_rc=1
    else
      if (( get_last - get_first + 1 > BN_GET_BLOCK_MAX_PER_CHECK )); then
        echo "  capping getBlock sweep to the most recent ${BN_GET_BLOCK_MAX_PER_CHECK} of $((get_last - get_first + 1)) in-range blocks"
        get_first=$((get_last - BN_GET_BLOCK_MAX_PER_CHECK + 1))
      fi
      echo "Calling BN getBlock for blocks ${get_first}..${get_last} (BN available range ${bn_first}..${bn_last})"
      local n resp status items ok_count=0
      local getblock_failures=()
      local getblock_err="${WORK_DIR}/bn-getblock.err"
      for (( n = get_first; n <= get_last; n++ )); do
        resp="$(bn_grpc_call "block-node/api/block_access_service.proto" \
          org.hiero.block.api.BlockAccessService/getBlock "{\"block_number\": ${n}}" 2>"${getblock_err}")" || true
        status="$(echo "${resp}" | jq -r '.status // empty' 2>/dev/null)" || true
        items="$(echo "${resp}" | jq -r '.block.items | length' 2>/dev/null)" || true
        if [[ "${status}" == "SUCCESS" && "${items}" =~ ^[0-9]+$ ]] && (( items > 0 )); then
          ok_count=$((ok_count + 1))
        else
          getblock_failures+=("block ${n}: status=${status:-<none>} items=${items:-?} grpcurl_err=$(tail -n 1 "${getblock_err}" 2>/dev/null || true)")
        fi
      done
      if (( ${#getblock_failures[@]} > 0 )); then
        echo "verify_mn_blocks_advancing_and_bn_serves: BN failed to serve ${#getblock_failures[@]}/$((get_last - get_first + 1)) blocks of the observed span:" >&2
        printf '  %s\n' "${getblock_failures[@]}" >&2
        sweep_rc=1
      else
        echo "BN served all ${ok_count} observed blocks (${get_first}..${get_last}) with SUCCESS and non-empty items"
        VALIDATION_SUMMARY_LINES+=("✓ BN getBlock: served all ${ok_count} observed blocks (${get_first}..${get_last}) with SUCCESS and non-empty items")
      fi
    fi
  fi
  stop_bn_port_forward
  return "${sweep_rc}"
}

# Every transaction nudge records its successful cryptoCreate and flush transaction IDs.
# Keep the ledger in GENERATED_DIR so failures can be traced to specific submitted transactions.
verify_submitted_transactions_visible_in_mirror() {
  local txid body seen total=0 pass
  local misses=() next_misses=()
  [[ -s "${TX_ID_OUT_FILE}" ]] || { echo "No submitted transactions recorded; continuity check cannot run" >&2; return 1; }
  while IFS= read -r txid; do
    [[ -n "${txid}" ]] || continue
    misses+=("${txid}")
    total=$((total + 1))
  done < "${TX_ID_OUT_FILE}"
  (( total > 0 )) || return 1
  for pass in 1 2 3; do
    next_misses=()
    for txid in "${misses[@]}"; do
      seen=false
      if body="$(curl -fsS --connect-timeout 5 --max-time 15 \
          "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/transactions/${txid}" 2>/dev/null)"; then
        seen="$(jq -r 'any(.transactions[]?; .result == "SUCCESS")' <<< "${body}" 2>/dev/null)" || true
      fi
      [[ "${seen}" == true ]] || next_misses+=("${txid}")
    done
    if (( ${#next_misses[@]} == 0 )); then
      log "PASS: all ${total} tracked transactions across the three stages are visible in MN with SUCCESS"
      VALIDATION_SUMMARY_LINES+=("Transaction continuity: all ${total} tracked transaction IDs visible in MN with SUCCESS")
      return 0
    fi
    misses=("${next_misses[@]}")
    (( pass == 3 )) || sleep 5
  done
  echo "TRANSACTION LOSS: ${#misses[@]}/${total} submitted transactions missing from MN or not SUCCESS:" >&2
  printf '  %s\n' "${misses[@]}" >&2
  return 1
}

validate_fresh_mirror_ingestion() {
  local tx_count="${1:-${MIRROR_VALIDATION_TX_COUNT}}"
  # The nudge closes the preceding record block as well as recording every receipt and account ID.
  NUDGE_TX_COUNT="${tx_count}" nudge_consensus_with_transactions
  [[ -s "${ACCOUNT_ID_OUT_FILE}" ]] || { echo "No new accounts recorded for mirror validation" >&2; return 1; }
  [[ "$(wc -l < "${ACCOUNT_ID_OUT_FILE}" | tr -d ' ')" == "${tx_count}" ]] || {
    echo "Fresh-account batch did not record the expected ${tx_count} accounts" >&2
    return 1
  }
  local account body deadline=$((SECONDS + MIRROR_ACCOUNT_WAIT_SECS))
  while IFS= read -r account; do
    [[ -n "${account}" ]] || continue
    while true; do
      body="$(curl -fsS --connect-timeout 5 --max-time 15 \
          "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/accounts/${account}" 2>/dev/null)" || body=""
      if jq -e --arg account "${account}" '.account == $account' <<< "${body}" >/dev/null 2>&1; then
        log "MN sees newly created account ${account} (${CURRENT_STAGE})"
        break
      fi
      case "${CURRENT_STAGE}" in
        post-*|stage3-*) fail_on_mirror_block_verification_error || return 1 ;;
      esac
      if (( SECONDS >= deadline )); then
        echo "MN did not ingest new account ${account} within ${MIRROR_ACCOUNT_WAIT_SECS}s (${CURRENT_STAGE})" >&2
        return 1
      fi
      sleep 2
    done
  done < "${ACCOUNT_ID_OUT_FILE}"
}

fail_on_mirror_block_verification_error() {
  local logs failure
  logs="$(kubectl --request-timeout=10s -n "${SOLO_NAMESPACE}" logs deployment/mirror-1-importer \
    --since=30s --tail=120 2>/dev/null)" || return 0
  failure="$(printf '%s\n' "${logs}" | grep -m 1 -E 'TSS signature verification failed for block|HashMismatchException|Non-consecutive block number' || true)"
  if [[ -n "${failure}" ]]; then
    echo "MN cannot verify the block stream: ${failure}" >&2
    echo "Check the importer block hash/signature compatibility; increasing account wait time will not resolve a verification rejection." >&2
    return 1
  fi
}

MINIO_RESTORE_RESOURCE=""
MINIO_RESTORE_REPLICAS=""

disconnect_importer_from_minio() {
  local context resource replicas pod
  # Require one controller for all MinIO server pods, then stop every pod it owns. A partial
  # disconnect would allow record ingestion to keep passing this supposedly BN-only check.
  context="$(kubectl --request-timeout=10s -n "${MINIO_NAMESPACE}" get pods -o json | jq -ce '
    [.items[] | select(any(.spec.containers[]?; .name == "minio"))]
    | select(length > 0)
    | {pods: map(.metadata.name), owners: (map(.metadata.ownerReferences[]? | select(.controller == true) | {kind, name}) | unique)}
    | select((.owners | length) == 1)
  ')" || { echo "Could not identify a single MinIO controller; cannot prove BN-only ingestion" >&2; return 1; }
  resource="$(jq -r '.owners[0] | .kind + "/" + .name' <<< "${context}")"
  case "${resource}" in
    StatefulSet/*|Deployment/*) ;;
    *) echo "Unsupported MinIO controller ${resource}" >&2; return 1 ;;
  esac
  replicas="$(kubectl --request-timeout=10s -n "${MINIO_NAMESPACE}" get "${resource}" -o jsonpath='{.spec.replicas}')"
  [[ "${replicas}" =~ ^[0-9]+$ ]] && (( replicas > 0 )) || return 1
  # Record restoration intent before changing the cluster, including a partially failed scale.
  MINIO_RESTORE_RESOURCE="${resource}"
  MINIO_RESTORE_REPLICAS="${replicas}"
  log "Stopping ${resource} (${replicas} replicas) to prove fresh ingestion through BN"
  kubectl --request-timeout=30s -n "${MINIO_NAMESPACE}" scale "${resource}" --replicas=0 || return 1
  while IFS= read -r pod; do
    kubectl --request-timeout=10s -n "${MINIO_NAMESPACE}" wait --for=delete "pod/${pod}" --timeout=120s || return 1
  done < <(jq -r '.pods[]' <<< "${context}")
}

reconnect_importer_to_minio() {
  [[ -n "${MINIO_RESTORE_RESOURCE}" ]] || return 0
  log "Restoring ${MINIO_RESTORE_RESOURCE} to ${MINIO_RESTORE_REPLICAS} replicas"
  local attempt scaled=false
  for attempt in 1 2 3; do
    if kubectl --request-timeout=10s -n "${MINIO_NAMESPACE}" scale "${MINIO_RESTORE_RESOURCE}" \
        --replicas="${MINIO_RESTORE_REPLICAS}"; then
      scaled=true
      break
    fi
    sleep 2
  done
  if [[ "${scaled}" != true ]]; then
    echo "Could not restore MinIO's replica target; the Kubernetes API may be unavailable" >&2
    return 1
  fi
  if ! kubectl --request-timeout=10s -n "${MINIO_NAMESPACE}" rollout status "${MINIO_RESTORE_RESOURCE}" \
      --timeout="${MINIO_RESTORE_TIMEOUT_SECS:-300}s"; then
    echo "MinIO replica target is restored, but readiness did not recover; check node health and resource pressure" >&2
    return 1
  fi
  MINIO_RESTORE_RESOURCE=""
  MINIO_RESTORE_REPLICAS=""
  # The pod was recreated; rediscover the counting endpoint and credentials before sampling again.
  MINIO_COUNT_POD=""
}

verify_minio_isolation_prerequisites() {
  log "Checking node, BN and MN health before MinIO isolation"
  kubectl --request-timeout=10s wait --for=condition=Ready node --all --timeout=30s || return 1
  deployment_ready mirror-1-importer 30 || return 1
  deployment_ready mirror-1-rest 30 || return 1
  kubectl --request-timeout=10s -n "${SOLO_NAMESPACE}" rollout status \
    "statefulset/block-node-${BLOCK_NODE_ID}" --timeout=30s || return 1
  fail_on_mirror_block_verification_error || return 1
  # A responding blocks endpoint can serve stale data. Require a new account to be ingested
  # while MinIO is still healthy, so a pre-existing ingestion failure cannot be blamed on isolation.
  validate_fresh_mirror_ingestion || return 1
}

verify_bn_ingestion_without_minio() {
  verify_minio_isolation_prerequisites
  disconnect_importer_from_minio
  validate_fresh_mirror_ingestion "${BN_ISOLATION_TX_COUNT}"
  reconnect_importer_to_minio
  VALIDATION_SUMMARY_LINES+=("BN-only ingestion: MN received ${BN_ISOLATION_TX_COUNT} fresh accounts while MinIO was stopped")
}

verify_post_tck_continuity() {
  CURRENT_STAGE="post-tck-validation"
  log "=== Final ingestion and transaction continuity checks ==="
  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
  start_mirror_rest_port_forward
  validate_fresh_mirror_ingestion
  verify_mn_blocks_advancing_and_bn_serves
  verify_submitted_transactions_visible_in_mirror
  verify_record_uploads_ceased_final
  printf 'PASS: %s\n' "${VALIDATION_SUMMARY_LINES[@]}"
}
