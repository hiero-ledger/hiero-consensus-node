#!/usr/bin/env bash
set -o pipefail
set +e

readonly RELEASE_LIB_PATH="hedera-node/data/lib"
readonly RELEASE_APPS_PATH="hedera-node/data/apps"

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
  export GITHUB_WORKSPACE GITHUB_SHA GITHUB_OUTPUT MANIFEST_PATH

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

  start_task "Checking for the sha256sum command"
    if command -v sha256sum >/dev/null 2>&1; then
      SHA256SUM="$(command -v sha256sum)" || fail "ERROR (Exit Code: ${?})" "${?}"
    else
      fail "ERROR (Exit Code: ${?})" "${?}"
    fi
  end_task "DONE (Found: ${SHA256SUM})"

  start_task "Checking for prebuilt libraries"
    ls -al "${GITHUB_WORKSPACE}/${RELEASE_LIB_PATH}"/*.jar >/dev/null 2>&1 || fail "ERROR (Exit Code: ${?})" "${?}"
  end_task "FOUND (Path: ${GITHUB_WORKSPACE}/${RELEASE_LIB_PATH}/*.jar)"

  start_task "Checking for prebuilt applications"
    ls -al "${GITHUB_WORKSPACE}/${RELEASE_APPS_PATH}"/*.jar >/dev/null 2>&1 || fail "ERROR (Exit Code: ${?})" "${?}"
  end_task "FOUND (Path: ${GITHUB_WORKSPACE}/${RELEASE_APPS_PATH}/*.jar)"
end_group

start_group "Generating Library Hashes (${GITHUB_WORKSPACE}/${RELEASE_LIB_PATH}/*.jar)"
  pushd "${GITHUB_WORKSPACE}/${RELEASE_LIB_PATH}" >/dev/null 2>&1 || fail "PUSHD ERROR (Exit Code: ${?})" "${?}"
  ${SHA256SUM} -b -- *.jar | sort -k 2 | tee -a "${TEMP_DIR}"/libraries.sha256
  popd >/dev/null 2>&1 || fail "POPD ERROR (Exit Code: ${?})" "${?}"
end_group

start_group "Generating Application Hashes (${GITHUB_WORKSPACE}/${RELEASE_APPS_PATH}/*.jar)"
  pushd "${GITHUB_WORKSPACE}/${RELEASE_APPS_PATH}" >/dev/null 2>&1 || fail "PUSHD ERROR (Exit Code: ${?})" "${?}"
  ${SHA256SUM} -b -- *.jar | sort -k 2 | tee -a "${TEMP_DIR}"/applications.sha256
  popd >/dev/null 2>&1 || fail "POPD ERROR (Exit Code: ${?})" "${?}"
end_group

start_group "Generating Final Release Manifests"

  start_task "Generating the manifest archive"
  tar -czf "${TEMP_DIR}/manifest.tar.gz" -C "${TEMP_DIR}" libraries.sha256 applications.sha256 >/dev/null 2>&1 || fail "TAR ERROR (Exit Code: ${?})" "${?}"
  end_task

  start_task "Copying the manifest files"
  cp "${TEMP_DIR}/manifest.tar.gz" "${MANIFEST_PATH}/${GITHUB_SHA}.tar.gz" || fail "COPY ERROR (Exit Code: ${?})" "${?}"
  cp "${TEMP_DIR}/libraries.sha256" "${MANIFEST_PATH}/libraries.sha256" || fail "COPY ERROR (Exit Code: ${?})" "${?}"
  cp "${TEMP_DIR}/applications.sha256" "${MANIFEST_PATH}/applications.sha256" || fail "COPY ERROR (Exit Code: ${?})" "${?}"
  end_task "DONE (Path: ${MANIFEST_PATH}/${GITHUB_SHA}.tar.gz)"

  start_task "Setting Step Outputs"
    {
      printf "path=%s\n" "${MANIFEST_PATH}"
      printf "file=%s\n" "${MANIFEST_PATH}/${GITHUB_SHA}.tar.gz"
      printf "name=%s\n" "${GITHUB_SHA}.tar.gz"
      printf "applications=%s\n" "${MANIFEST_PATH}/applications.sha256"
      printf "libraries=%s\n" "${MANIFEST_PATH}/libraries.sha256"
    } >> "${GITHUB_OUTPUT}"
  end_task
end_group

