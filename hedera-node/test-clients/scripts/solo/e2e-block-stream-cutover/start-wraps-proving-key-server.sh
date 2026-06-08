#!/usr/bin/env bash
# Serves wraps proving key tarball over HTTP via Docker/nginx.
set -euo pipefail

WRAPS_TAR_PATH="${WRAPS_TAR_PATH:-${HOME}/.solo/cache/wraps-v1.0.0.tar.gz}"
WRAPS_SERVER_PORT="${WRAPS_SERVER_PORT:-8089}"
WRAPS_SERVER_CONTAINER_NAME="${WRAPS_SERVER_CONTAINER_NAME:-wraps-proving-key-server}"
WRAPS_SERVER_IMAGE="${WRAPS_SERVER_IMAGE:-nginx:alpine}"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required but was not found in PATH" >&2
  exit 1
fi

if [[ ! -f "${WRAPS_TAR_PATH}" ]]; then
  echo "Wraps tarball not found: ${WRAPS_TAR_PATH}" >&2
  exit 1
fi

WRAPS_FILENAME="$(basename "${WRAPS_TAR_PATH}")"
WRAPS_DIR="$(cd "$(dirname "${WRAPS_TAR_PATH}")" && pwd)"

# Replace any existing container using the same name.
docker rm -f "${WRAPS_SERVER_CONTAINER_NAME}" >/dev/null 2>&1 || true

docker run -d \
  --name "${WRAPS_SERVER_CONTAINER_NAME}" \
  --restart unless-stopped \
  -p "${WRAPS_SERVER_PORT}:80" \
  -v "${WRAPS_DIR}:/usr/share/nginx/html:ro" \
  "${WRAPS_SERVER_IMAGE}" >/dev/null

# Wait briefly for nginx to become reachable.
ready=0
for _ in {1..20}; do
  if curl -sf "http://127.0.0.1:${WRAPS_SERVER_PORT}/${WRAPS_FILENAME}" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 1
done

if [[ "${ready}" != "1" ]]; then
  echo "Server container started, but file is not reachable yet." >&2
  echo "Check logs: docker logs ${WRAPS_SERVER_CONTAINER_NAME}" >&2
  exit 1
fi

LOCAL_URL="http://127.0.0.1:${WRAPS_SERVER_PORT}/${WRAPS_FILENAME}"
KIND_URL="http://host.docker.internal:${WRAPS_SERVER_PORT}/${WRAPS_FILENAME}"

echo "Wraps proving key server is running."
echo "Container: ${WRAPS_SERVER_CONTAINER_NAME}"
echo "Local URL: ${LOCAL_URL}"
echo "Kind/CN URL: ${KIND_URL}"
echo
echo "Use this in application.properties:"
echo "tss.wrapsProvingKeyDownloadUrl=${KIND_URL}"
