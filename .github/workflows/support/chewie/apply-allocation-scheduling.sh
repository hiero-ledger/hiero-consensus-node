#!/usr/bin/env bash
set -euo pipefail

# Renders a Chewie allocation's scheduling constraints into Helm values files, in place.
#
# Each pod spec in a CITR values file names the allocation group it must run on with a placeholder
# nodeSelector entry, "chewie-group: cn" or "chewie-group: aux". For every such spec the placeholder
# is replaced by that group's labels, keeping any other nodeSelector entries (the block node's
# hostname), and the group's tolerations are appended to the spec's own.
#
# Labels and tolerations are the compact JSON parse-chewie-allocation.sh emits as the cn-/aux-labels
# and cn-/aux-tolerations outputs, read from CN_LABELS, CN_TOLERATIONS, AUX_LABELS, AUX_TOLERATIONS.

YQ_CLI_VERSION="${YQ_CLI_VERSION:-4.54.1}"

# The render expression needs mikefarah yq v4. The runners are expected to carry it; if they don't,
# or carry the Python jq wrapper of the same name instead, install the pinned release.
ensure_yq() {
  if yq --version 2>&1 | grep -q "mikefarah"; then
    return 0
  fi

  echo "::group::Installing YQ CLI"
  sudo curl -SsL "https://github.com/mikefarah/yq/releases/download/v${YQ_CLI_VERSION}/yq_linux_amd64" -o /usr/local/bin/yq
  sudo chmod +x /usr/local/bin/yq
  echo "::endgroup::"
}

apply_allocation_scheduling() {
  if [[ $# -eq 0 ]]; then
    echo "Usage: ${0} <values-file>..." >&2
    return 1
  fi

  ensure_yq

  # A nodeSelector built from an empty label set matches every machine, so refuse one up front.
  if ! jq -e 'type == "object" and length > 0' <<< "${CN_LABELS:-null}" > /dev/null \
    || ! jq -e 'type == "object" and length > 0' <<< "${AUX_LABELS:-null}" > /dev/null; then
    echo "Error: CN_LABELS and AUX_LABELS must both be non-empty JSON objects" >&2
    return 1
  fi
  if ! jq -e 'type == "array"' <<< "${CN_TOLERATIONS:-null}" > /dev/null \
    || ! jq -e 'type == "array"' <<< "${AUX_TOLERATIONS:-null}" > /dev/null; then
    echo "Error: CN_TOLERATIONS and AUX_TOLERATIONS must both be JSON arrays" >&2
    return 1
  fi

  local file
  for file in "$@"; do
    GROUP=cn LABELS="${CN_LABELS}" TOLERATIONS="${CN_TOLERATIONS}" yq -i "${RENDER_EXPRESSION}" "${file}"
    GROUP=aux LABELS="${AUX_LABELS}" TOLERATIONS="${AUX_TOLERATIONS}" yq -i "${RENDER_EXPRESSION}" "${file}"

    # Anything left is a placeholder naming neither group; its pod would never schedule.
    if yq -e '[.. | select(tag == "!!map" and has("chewie-group"))] | length > 0' "${file}" > /dev/null 2>&1; then
      echo "Error: ${file} has a chewie-group placeholder other than cn or aux" >&2
      return 1
    fi
  done
}

RENDER_EXPRESSION='
  (.. | select(tag == "!!map" and has("nodeSelector")) | select(.nodeSelector["chewie-group"] == strenv(GROUP))) |= (
    .nodeSelector = ((.nodeSelector | del(.["chewie-group"])) * env(LABELS))
    | .tolerations = ((.tolerations // []) + env(TOLERATIONS))
  )'

apply_allocation_scheduling "$@"
