# SPDX-License-Identifier: Apache-2.0
#
# Shared helpers for running the Solo WRB scenarios against the pre-allocated REMOTE multi-tenant
# cluster (solo-sdlt-n6 via Teleport). Source this from a scenario script:
#
#   source "${SCRIPT_DIR}/../remote-cluster-helpers.sh"
#
# It expects the sourcing script to have already set: SOLO_NAMESPACE, SOLO_DEPLOYMENT, CLUSTER_REF,
# CONSENSUS_NODE_COUNT, SOLO_CLUSTER_SETUP_NAMESPACE, CLUSTER_TARGET, and a `log` function.
#
# Background: the remote cluster (a) taints every worker solo.hashgraph.io/owner=...; (b) has the
# "local-path" StorageClass but no default; (c) Solo's "consensus network destroy" deletes the
# deployment's solo-remote-config ConfigMap. The jumpstart script (solo-wrb-jumpstart.sh) carries the
# same logic inline (proven); these helpers let the streaming/cutover scripts reuse it verbatim.

# Path to the consensus network-deploy value overrides (scheduling tolerations + MinIO storage class).
# Pass it to `solo consensus network deploy --values-file` on remote (with --pvcs false).
REMOTE_CLUSTER_HELPERS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REMOTE_CLUSTER_NETWORK_VALUES="${REMOTE_CLUSTER_NETWORK_VALUES:-${REMOTE_CLUSTER_HELPERS_DIR}/remote-cluster-network-values.yaml}"

# Reset and re-prepare the deployment on the shared remote cluster, after the initial
# cluster-ref connect + deployment create + cluster attach. Marks local-path the default
# StorageClass, tears down any pre-existing network, re-establishes the deployment config the
# destroy removes, and runs cluster-ref setup only if missing (skipping MinIO when the operator
# is present, never installing the prometheus stack on the shared cluster).
remote_reset_and_prepare_deployment() {
  kubectl patch storageclass local-path \
    -p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}' >/dev/null 2>&1 \
    && log "Marked StorageClass local-path as cluster default" \
    || log "Warning: could not mark local-path as default StorageClass (PVCs may stay Pending)"

  log "Remote target: destroying any pre-existing consensus network in ${SOLO_NAMESPACE}"
  solo consensus network destroy --deployment "${SOLO_DEPLOYMENT}" --delete-pvcs --delete-secrets --force --quiet-mode >/dev/null 2>&1 || true

  # network destroy removes the deployment's solo-remote-config ConfigMap, which key generation and
  # network deploy must read; re-establish the deployment config + cluster attachment.
  solo deployment config delete --deployment "${SOLO_DEPLOYMENT}" --quiet-mode >/dev/null 2>&1 || true
  solo deployment config create -n "${SOLO_NAMESPACE}" --deployment "${SOLO_DEPLOYMENT}"
  solo deployment cluster attach --deployment "${SOLO_DEPLOYMENT}" --cluster-ref "${CLUSTER_REF}" --num-consensus-nodes "${CONSENSUS_NODE_COUNT}"

  if helm list --all-namespaces 2>/dev/null | grep -q "solo-cluster-setup"; then
    log "cluster-setup release already present; skipping cluster-ref config setup"
  else
    local minio_flag="--minio"
    if kubectl get pods -l app.kubernetes.io/instance=minio-operator --all-namespaces --no-headers 2>/dev/null | grep -q .; then
      minio_flag="--no-minio"
    fi
    solo cluster-ref config setup -s "${SOLO_CLUSTER_SETUP_NAMESPACE}" --no-prometheus-stack "${minio_flag}" || true
  fi
}

# Background loop that keeps the namespace's NON-consensus workloads (mirror, shared-resources
# postgres/redis, block nodes, explorer) tolerating the node taint: solo's mirror/shared-resources/
# block-node sub-charts carry no toleration for solo.hashgraph.io/owner and expose no values knob.
# Skips network-node/haproxy/envoy/minio so the already-running consensus network is never restarted,
# and deletes already-Pending pods so the controllers recreate them from the patched template.
REMOTE_TOLERATION_PATCHER_PID="${REMOTE_TOLERATION_PATCHER_PID:-}"
start_remote_toleration_patcher() {
  [[ "${CLUSTER_TARGET}" == "remote" ]] || return 0
  (
    set +e
    # Long backstop (2h) so the loop survives the full cutover flow; scenarios stop it explicitly
    # once the relevant non-consensus workloads are up, and cleanup() stops it on exit.
    deadline=$(( $(date +%s) + 7200 ))
    while [[ "$(date +%s)" -lt "${deadline}" ]]; do
      for res in statefulset deployment; do
        kubectl -n "${SOLO_NAMESPACE}" get "${res}" \
          -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null | while IFS= read -r name; do
          [[ -n "${name}" ]] || continue
          case "${name}" in
            network-node*|haproxy*|envoy*|minio*) continue ;;
          esac
          kubectl -n "${SOLO_NAMESPACE}" patch "${res}" "${name}" --type merge \
            -p '{"spec":{"template":{"spec":{"tolerations":[{"operator":"Exists"}]}}}}' >/dev/null 2>&1 || true
        done
      done
      kubectl -n "${SOLO_NAMESPACE}" get pods --field-selector=status.phase=Pending \
        -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null | while IFS= read -r pod; do
        [[ -n "${pod}" ]] || continue
        case "${pod}" in
          network-node*|haproxy*|envoy*|minio*) continue ;;
        esac
        if ! kubectl -n "${SOLO_NAMESPACE}" get pod "${pod}" -o json 2>/dev/null \
            | jq -e '.spec.tolerations[]? | select(.operator=="Exists" and (.key==null))' >/dev/null 2>&1; then
          kubectl -n "${SOLO_NAMESPACE}" delete pod "${pod}" --wait=false >/dev/null 2>&1 || true
        fi
      done
      sleep 5
    done
  ) &
  REMOTE_TOLERATION_PATCHER_PID=$!
  log "Started remote toleration patcher (pid ${REMOTE_TOLERATION_PATCHER_PID}) for non-consensus workloads in ${SOLO_NAMESPACE}"
}
stop_remote_toleration_patcher() {
  [[ -n "${REMOTE_TOLERATION_PATCHER_PID:-}" ]] || return 0
  kill "${REMOTE_TOLERATION_PATCHER_PID}" >/dev/null 2>&1 || true
  wait "${REMOTE_TOLERATION_PATCHER_PID}" 2>/dev/null || true
  REMOTE_TOLERATION_PATCHER_PID=""
}
