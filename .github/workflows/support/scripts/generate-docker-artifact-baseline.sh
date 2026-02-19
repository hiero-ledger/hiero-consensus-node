#!/usr/bin/env bash
set -o pipefail
set +e

readonly DOCKER_IMAGE_NAME="consensus-node"

GROUP_ACTIVE="false"

function fail {
    printf '%s\n' "$1" >&2  ## Send message to stderr. Exclude >&2 if you don't want it that way.
    if [[ "${GROUP_ACTIVE}" == "true" ]]; then
      end_group
    fi
    exit "${2-1}"  ## Return a code specified by $2 or 1 by default.
}

function start_group {
  if [[ "${GROUP_ACTIVE}" == "true" ]]; then
    end_group
  fi

  GROUP_ACTIVE="true"
  printf "::group::%s\n" "${1}"
}

function end_group {
  GROUP_ACTIVE="false"
  printf "::endgroup::\n"
}

function log {
  local message="${1}"
  shift
  # shellcheck disable=SC2059
  printf "${message}" "${@}"
}

function log_line {
  local message="${1}"
  shift
  # shellcheck disable=SC2059
  printf "${message}\n" "${@}"
}

function start_task {
  local message="${1}"
  shift
  # shellcheck disable=SC2059
  printf "${message} .....\t" "${@}"
}

function end_task {
  printf "%s\n" "${1:-DONE}"
}

start_group "Configuring Environment"
  # Access workflow environment variables
  export GITHUB_WORKSPACE GITHUB_SHA GITHUB_OUTPUT MANIFEST_PATH DOCKER_REGISTRY DOCKER_TAG SKOPEO_VERSION SKOPEO_IMAGE_NAME

  start_task "Initializing Temporary Directory"
    TEMP_DIR="$(mktemp -d)" || fail "ERROR (Exit Code: ${?})" "${?}"
    trap 'rm -rf "${TEMP_DIR}"' EXIT
  end_task "DONE (Path: ${TEMP_DIR})"

  start_task "Resolving the GITHUB_WORKSPACE path"
    # Ensure GITHUB_WORKSPACE is provided or default to the repository root
    if [[ -z "${GITHUB_WORKSPACE}" || ! -d "${GITHUB_WORKSPACE}" ]]; then
      GITHUB_WORKSPACE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../../" && pwd)"
    fi
  end_task "DONE (Path: ${GITHUB_WORKSPACE})"

  start_task "Resolving the GITHUB_OUTPUT path"
    # Ensure GITHUB_OUTPUT is provided or default to the repository root
    if [[ -z "${GITHUB_OUTPUT}" ]]; then
      GITHUB_OUTPUT="${TEMP_DIR}/workflow-output.txt"
    fi
  end_task "DONE (Path: ${GITHUB_OUTPUT})"

  start_task "Resolving the GITHUB_SHA hash"
    if [[ -z "${GITHUB_SHA}" ]]; then
      GITHUB_SHA="$(git rev-parse HEAD | tr -d '[:space:]')" || fail "ERROR (Exit Code: ${?})" "${?}"
    fi
  end_task "DONE (Commit: ${GITHUB_SHA})"

  start_task "Resolving the MANIFEST_PATH variable"
    if [[ -z "${MANIFEST_PATH}" ]]; then
      MANIFEST_PATH="${GITHUB_WORKSPACE}/.manifests/gradle"
    fi
  end_task "DONE (Path: ${MANIFEST_PATH})"

  start_task "Ensuring the MANIFEST_PATH location is present"
    if [[ ! -d "${MANIFEST_PATH}" ]]; then
      mkdir -p "${MANIFEST_PATH}" || fail "ERROR (Exit Code: ${?})" "${?}"
    fi
  end_task

  start_task "Resolving the SKOPEO_VERSION variable"
    if [[ -z "${SKOPEO_VERSION}" ]]; then
      SKOPEO_VERSION="v1.14.0"
    fi
  end_task "DONE (Version: ${SKOPEO_VERSION})"

  start_task "Resolving the SKOPEO_IMAGE_NAME variable"
    if [[ -z "${SKOPEO_IMAGE_NAME}" ]]; then
      SKOPEO_IMAGE_NAME="quay.io/skopeo/stable:${SKOPEO_VERSION}"
    fi
  end_task "DONE (Image Name: ${SKOPEO_IMAGE_NAME})"

  start_task "Checking for the DOCKER command"
    if command -v docker >/dev/null 2>&1; then
      DOCKER="$(command -v docker)" || fail "ERROR (Exit Code: ${?})" "${?}"
      export DOCKER
    else
      fail "ERROR (Exit Code: ${?})" "${?}"
    fi
  end_task "DONE (Found: ${DOCKER})"

  start_task "Resolving the Docker Client Configuration"
    SKOPEO_BIND_MOUNT=""
    SKOPEO_CREDENTIAL_OPTS=""
    DOCKER_CONFIG_DIR="${HOME}/.docker"
    if [[ -d "${DOCKER_CONFIG_DIR}" ]]; then
      SKOPEO_BIND_MOUNT="--volume ${DOCKER_CONFIG_DIR}:/tmp/docker"
      SKOPEO_CREDENTIAL_OPTS="--authfile /tmp/docker/config.json"
    fi
    export SKOPEO_BIND_MOUNT SKOPEO_CREDENTIAL_OPTS
  end_task "DONE"

  start_task "Checking for the SKOPEO command"
    if command -v skopeo >/dev/null 2>&1; then
      SKOPEO="$(command -v skopeo)" || fail "ERROR (Exit Code: ${?})" "${?}"
      export SKOPEO
    else
      ${DOCKER} pull "${SKOPEO_IMAGE_NAME}" >/dev/null 2>&1 || fail "ERROR (Exit Code: ${?})" "${?}"
      SKOPEO="${DOCKER} run ${SKOPEO_BIND_MOUNT} --rm --network host ${SKOPEO_IMAGE_NAME}"
      export SKOPEO
    fi
  end_task "DONE (Found: ${SKOPEO})"

  start_task "Checking for the JQ command"
    if command -v jq >/dev/null 2>&1; then
      JQ="$(command -v jq)" || fail "ERROR (Exit Code: ${?})" "${?}"
      export JQ
    else
      fail "ERROR (Exit Code: ${?})" "${?}"
    fi
  end_task "DONE (Found: ${JQ})"
end_group

start_group "Prepare the Docker Image Information"
  start_task "Resolving the DOCKER_REGISTRY variable"
    if [[ -z "${DOCKER_REGISTRY}" ]]; then
      DOCKER_REGISTRY="localhost:5000"
    fi
  end_task "DONE (Registry: ${DOCKER_REGISTRY})"

  start_task "Resolving the DOCKER_TAG variable"
    if [[ -z "${DOCKER_TAG}" ]]; then
      DOCKER_TAG="$(echo "${GITHUB_SHA}" | tr -d '[:space:]' | cut -c1-8)"
    fi
  end_task "DONE (Tag: ${DOCKER_TAG})"

  start_task "Resolving the Fully Qualified Image Name"
    FQ_IMAGE_NAME="${DOCKER_REGISTRY}/${DOCKER_IMAGE_NAME}:${DOCKER_TAG}"
  end_task "DONE (Image: ${FQ_IMAGE_NAME})"
end_group

start_group "Generate Docker Image Manifest (linux/amd64)"
  ${SKOPEO} --override-os linux --override-arch amd64 inspect ${SKOPEO_CREDENTIAL_OPTS} --tls-verify=false "docker://${FQ_IMAGE_NAME}" | tee "${TEMP_DIR}/linux-amd64.manifest.json" || fail "SKOPEO ERROR (Exit Code: ${?})" "${?}"
  ${JQ} -r '.Layers[]' "${TEMP_DIR}/linux-amd64.manifest.json" | tee "${TEMP_DIR}/linux-amd64.layers.json" >/dev/null 2>&1 || fail "JQ LAYER ERROR (Exit Code: ${?})" "${?}"
  ${JQ} -r 'del(.RepoTags) | del(.LayersData) | del(.Digest) | del(.Name)' "${TEMP_DIR}/linux-amd64.manifest.json" | tee "${TEMP_DIR}/linux-amd64.comparable.json" >/dev/null 2>&1 || fail "JQ COMP ERROR (Exit Code: ${?})" "${?}"
end_group

start_group "Generate Docker Image Manifest (linux/arm64)"
  ${SKOPEO} --override-os linux --override-arch arm64 inspect ${SKOPEO_CREDENTIAL_OPTS} --tls-verify=false "docker://${FQ_IMAGE_NAME}" | tee "${TEMP_DIR}/linux-arm64.manifest.json" || fail "SKOPEO ERROR (Exit Code: ${?})" "${?}"
  ${JQ} -r '.Layers[]' "${TEMP_DIR}/linux-arm64.manifest.json" | tee "${TEMP_DIR}/linux-arm64.layers.json" >/dev/null 2>&1 || fail "JQ LAYER ERROR (Exit Code: ${?})" "${?}"
  ${JQ} -r 'del(.RepoTags) | del(.LayersData) | del(.Digest) | del(.Name)' "${TEMP_DIR}/linux-arm64.manifest.json" | tee "${TEMP_DIR}/linux-arm64.comparable.json" >/dev/null 2>&1 || fail "JQ COMP ERROR (Exit Code: ${?})" "${?}"
end_group

start_group "Generating Final Release Manifests"

  start_task "Generating the manifest archive"
    MANIFEST_FILES=("linux-amd64.manifest.json" "linux-amd64.layers.json" "linux-amd64.comparable.json")
    MANIFEST_FILES+=("linux-arm64.manifest.json" "linux-arm64.layers.json" "linux-arm64.comparable.json")
    tar -czf "${TEMP_DIR}/manifest.tar.gz" -C "${TEMP_DIR}" "${MANIFEST_FILES[@]}" >/dev/null 2>&1 || fail "TAR ERROR (Exit Code: ${?})" "${?}"
  end_task

  start_task "Copying the manifest files"
    cp "${TEMP_DIR}/manifest.tar.gz" "${MANIFEST_PATH}/${GITHUB_SHA}.tar.gz" || fail "COPY ERROR (Exit Code: ${?})" "${?}"
    cp "${TEMP_DIR}"/*.json "${MANIFEST_PATH}/" || fail "COPY ERROR (Exit Code: ${?})" "${?}"
  end_task "DONE (Path: ${MANIFEST_PATH}/${GITHUB_SHA}.tar.gz)"

  start_task "Setting Step Outputs"
    {
      printf "path=%s\n" "${MANIFEST_PATH}"
      printf "file=%s\n" "${MANIFEST_PATH}/${GITHUB_SHA}.tar.gz"
      printf "name=%s\n" "${GITHUB_SHA}.tar.gz"
    } >> "${GITHUB_OUTPUT}"
  end_task
end_group

