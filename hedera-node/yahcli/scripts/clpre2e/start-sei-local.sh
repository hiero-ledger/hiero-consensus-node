#!/usr/bin/env bash
#
# Start one local Sei node using the sibling sei-chain localnode Docker image.
#
# Usage:
#   ./start-sei-local.sh          # start detached
#   ./start-sei-local.sh down     # remove container
#   ./start-sei-local.sh logs     # follow docker logs
#   ./start-sei-local.sh status
#
# Useful memory knobs:
#   SEI_MEMORY=8g SEI_CPUS=2 GOMEMLIMIT=6GiB GOGC=50 ./start-sei-local.sh
#   SEI_SKIP_BUILD=true ./start-sei-local.sh  # after a successful first build
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
CONTAINER_NAME="${SEI_CONTAINER_NAME:-sei-node}"
SEI_IMAGE="${SEI_IMAGE:-sei-chain/localnode}"
SEI_EVM_RPC="${SEI_EVM_RPC:-http://127.0.0.1:8545}"
SEI_TM_RPC="${SEI_TM_RPC:-http://127.0.0.1:26657}"
CMD="${1:-up}"

die() { echo "FAIL $*" >&2; exit 1; }
note() { echo "$*" >&2; }

resolve_sei_repo() {
    if [[ -n "${SEI_CHAIN_REPO:-}" ]]; then
        cd "${SEI_CHAIN_REPO}" && pwd
        return
    fi
    for candidate in "${REPO_ROOT}/../sei-chain" "${REPO_ROOT}/../../sei-chain"; do
        if [[ -d "${candidate}/docker/localnode" ]]; then
            cd "${candidate}" && pwd
            return
        fi
    done
    return 1
}

docker_platform() {
    if [[ -n "${DOCKER_PLATFORM:-}" ]]; then
        echo "${DOCKER_PLATFORM}"
        return
    fi
    case "$(uname -m)" in
        arm64|aarch64) echo "linux/arm64" ;;
        *) echo "linux/amd64" ;;
    esac
}

docker_network_args() {
    local mode="${SEI_DOCKER_NETWORK_MODE:-}"
    if [[ -z "${mode}" ]]; then
        case "$(uname -s)" in
            Darwin) mode="publish" ;;
            *) mode="host" ;;
        esac
    fi

    case "${mode}" in
        host)
            echo "--network host"
            ;;
        publish)
            echo "-p ${SEI_TM_PORT:-26657}:26657 -p ${SEI_EVM_PORT:-8545}:8545 -p ${SEI_API_PORT:-1317}:1317 -p ${SEI_GRPC_PORT:-9090}:9090"
            ;;
        *)
            die "SEI_DOCKER_NETWORK_MODE must be 'publish' or 'host'"
            ;;
    esac
}

json_rpc_ok() {
    local url="$1" method="$2"
    curl -fsS -X POST "${url}" \
        -H 'Content-Type: application/json' \
        --data "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"${method}\",\"params\":[]}" \
        >/dev/null 2>&1
}

wait_for_rpc() {
    local deadline=$((SECONDS + ${SEI_WAIT_SECONDS:-180}))
    while (( SECONDS < deadline )); do
        if curl -fsS "${SEI_TM_RPC}/status" >/dev/null 2>&1 \
            && json_rpc_ok "${SEI_EVM_RPC}" eth_chainId; then
            return 0
        fi
        sleep 3
    done
    return 1
}

case "${CMD}" in
    down)
        docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
        note "Removed ${CONTAINER_NAME}."
        exit 0
        ;;
    logs)
        exec docker logs -f "${CONTAINER_NAME}"
        ;;
    status)
        docker ps --filter "name=^/${CONTAINER_NAME}$" --format '{{.Names}} {{.Status}}'
        exit 0
        ;;
    up) ;;
    *) die "usage: $0 [up|down|logs|status]" ;;
esac

for tool in docker curl; do
    command -v "${tool}" >/dev/null 2>&1 || die "${tool} not found on PATH"
done

SEI_CHAIN_REPO="$(resolve_sei_repo)" || die "sei-chain repo not found. Set SEI_CHAIN_REPO=/path/to/sei-chain"
PLATFORM="$(docker_platform)"
DEFAULT_SEI_IMAGE="sei-chain/localnode"
GOCACHE_HOST="${GOCACHE:-}"
if [[ -z "${GOCACHE_HOST}" ]] && command -v go >/dev/null 2>&1; then
    GOCACHE_HOST="$(go env GOCACHE 2>/dev/null || true)"
fi
GOCACHE_HOST="${GOCACHE_HOST:-${HOME}/Library/Caches/go-build}"
GOMODCACHE_HOST="${GOMODCACHE:-}"
if [[ -z "${GOMODCACHE_HOST}" ]] && command -v go >/dev/null 2>&1; then
    GOMODCACHE_HOST="$(go env GOMODCACHE 2>/dev/null || true)"
fi
GOMODCACHE_HOST="${GOMODCACHE_HOST:-${HOME}/go/pkg/mod}"
mkdir -p "${GOCACHE_HOST}" "${GOMODCACHE_HOST}"

IMAGE_ARCH="$(docker image inspect "${SEI_IMAGE}" --format '{{.Architecture}}' 2>/dev/null || true)"
EXPECTED_ARCH="${PLATFORM#linux/}"
if [[ -z "${IMAGE_ARCH}" || "${IMAGE_ARCH}" != "${EXPECTED_ARCH}" ]]; then
    if [[ "${SEI_IMAGE}" == "${DEFAULT_SEI_IMAGE}" || "${SEI_IMAGE}" == "${DEFAULT_SEI_IMAGE}:latest" ]]; then
        note "Building sei-chain/localnode:latest for ${PLATFORM}..."
        DOCKER_PLATFORM="${PLATFORM}" make -C "${SEI_CHAIN_REPO}" build-docker-node
    elif [[ -z "${IMAGE_ARCH}" ]]; then
        note "Custom SEI_IMAGE=${SEI_IMAGE} is not present in Docker context '$(docker context show 2>/dev/null || echo default)'."
        note "Use the repo-built image by omitting SEI_IMAGE, or tag the existing image:"
        note "  docker tag ${DEFAULT_SEI_IMAGE}:latest ${SEI_IMAGE}:latest"
        die "custom SEI_IMAGE missing locally"
    else
        note "Using custom SEI_IMAGE=${SEI_IMAGE} with arch=${IMAGE_ARCH:-unknown}; Docker will validate it at run time."
    fi
fi

docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true

if [[ "${KEEP_SEI_STATE:-false}" != "true" ]]; then
    rm -rf "${SEI_CHAIN_REPO}/build/generated"
fi

docker_env=(
    -e "NUM_ACCOUNTS=${NUM_ACCOUNTS:-1}"
    -e "GOMAXPROCS=${GOMAXPROCS:-2}"
    -e "GOMEMLIMIT=${GOMEMLIMIT:-6GiB}"
    -e "GOGC=${GOGC:-50}"
    -e "MOCK_BALANCES=${MOCK_BALANCES:-true}"
    -e "SEI_PREFUND_ACCOUNTS=${SEI_PREFUND_ACCOUNTS-sei17w0adeg64ky0daxwd2ugyuneellmjgnxw32ydp}"
    -e "SEI_PREFUND_AMOUNT=${SEI_PREFUND_AMOUNT:-1000000000000000000000}"
    -e "SEI_HISTORICAL_PROOF_MAX_INFLIGHT=${SEI_HISTORICAL_PROOF_MAX_INFLIGHT:-16}"
    -e "SEI_HISTORICAL_PROOF_RATE_LIMIT=${SEI_HISTORICAL_PROOF_RATE_LIMIT:-0}"
    -e "SEI_HISTORICAL_PROOF_BURST=${SEI_HISTORICAL_PROOF_BURST:-64}"
)
if [[ "${SEI_SKIP_BUILD:-false}" == "true" ]]; then
    docker_env+=(-e "SKIP_BUILD=true")
fi

read -r -a NETWORK_ARGS <<< "$(docker_network_args)"

note "Starting ${CONTAINER_NAME} (${SEI_IMAGE}, ${PLATFORM}, memory=${SEI_MEMORY:-8g}, cpus=${SEI_CPUS:-2})..."
docker run -d --rm \
    --name "${CONTAINER_NAME}" \
    "${NETWORK_ARGS[@]}" \
    --user="$(id -u):$(id -g)" \
    --memory="${SEI_MEMORY:-8g}" \
    --memory-swap="${SEI_MEMORY_SWAP:-${SEI_MEMORY:-8g}}" \
    --cpus="${SEI_CPUS:-2}" \
    -v "${SCRIPT_DIR}/sei-genesis-with-deployer.sh:/usr/bin/genesis.sh:ro" \
    -v "${SCRIPT_DIR}/sei-config-override.sh:/usr/bin/config_override.sh:ro" \
    -v "${SEI_CHAIN_REPO}:/sei-protocol/sei-chain:Z" \
    -v "${GOMODCACHE_HOST}:/root/go/pkg/mod:Z" \
    -v "${GOCACHE_HOST}:/root/.cache/go-build:Z" \
    --platform "${PLATFORM}" \
    "${docker_env[@]}" \
    "${SEI_IMAGE}" >/dev/null

if wait_for_rpc; then
    note "Sei is up."
    note "  EVM RPC     : ${SEI_EVM_RPC}"
    note "  CometBFT RPC: ${SEI_TM_RPC}"
    note "  Logs        : docker logs -f ${CONTAINER_NAME}"
else
    note "Sei did not become ready before timeout. Last logs:"
    docker logs --tail 80 "${CONTAINER_NAME}" >&2 || true
    exit 1
fi
