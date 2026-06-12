#!/usr/bin/env bash
# Build the NMT-baked consensus-node image and side-load it into the active
# kind cluster, so Solo can pull it with imagePullPolicy=Never.
#
# Idempotent: skips the docker build if the image already exists locally
# (override with REBUILD=1) and skips the kind load if the image is already
# present in the cluster nodes (override with REIMPORT=1).
#
# Issue: hiero-ledger/hiero-consensus-node#25736
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

: "${IMAGE_REPO:=solo-nmt-network-node}"
: "${IMAGE_TAG:=v1.3.4-jdk25}"
: "${KIND_CLUSTER:=solo-migration}"
: "${SOLO_BASE_IMAGE:=ghcr.io/hashgraph/solo-containers/ubi8-s6-java25:0.43.0}"
: "${REBUILD:=0}"
: "${REIMPORT:=0}"

IMAGE_REF="localhost/${IMAGE_REPO}:${IMAGE_TAG}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }

build_image() {
  if [[ "${REBUILD}" != "1" ]] && docker image inspect "${IMAGE_REF}" >/dev/null 2>&1; then
    log "image ${IMAGE_REF} already present locally (REBUILD=1 to force)"
    return 0
  fi
  log "building ${IMAGE_REF} from ${SOLO_BASE_IMAGE}"
  docker build \
    --platform linux/amd64 \
    --build-arg "SOLO_BASE_IMAGE=${SOLO_BASE_IMAGE}" \
    --tag "${IMAGE_REF}" \
    --progress plain \
    "${SCRIPT_DIR}"
}

load_into_kind() {
  if ! command -v kind >/dev/null 2>&1; then
    log "WARN: kind not on PATH; skipping side-load (CI: built image must be reachable some other way)"
    return 0
  fi
  if ! kind get clusters 2>/dev/null | grep -qx "${KIND_CLUSTER}"; then
    log "WARN: kind cluster ${KIND_CLUSTER} not running; skipping side-load"
    return 0
  fi
  if [[ "${REIMPORT}" != "1" ]]; then
    # crictl inside the kind node is the source of truth — `kind get images`
    # doesn't exist. Just probe one node:
    local first_node
    first_node="$(kind get nodes --name "${KIND_CLUSTER}" | head -1)"
    if [[ -n "${first_node}" ]] && \
       docker exec "${first_node}" crictl images 2>/dev/null | grep -q "${IMAGE_REPO}.*${IMAGE_TAG}"; then
      log "image ${IMAGE_REF} already loaded in kind cluster ${KIND_CLUSTER} (REIMPORT=1 to force)"
      return 0
    fi
  fi
  log "side-loading ${IMAGE_REF} into kind cluster ${KIND_CLUSTER}"
  kind load docker-image "${IMAGE_REF}" --name "${KIND_CLUSTER}"
}

main() {
  build_image
  load_into_kind
  log "done — Solo can deploy with imagePullPolicy=Never against ${IMAGE_REF}"
}

main "$@"
