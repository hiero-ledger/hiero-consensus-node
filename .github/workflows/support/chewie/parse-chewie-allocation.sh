#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Fetches a Chewie compute allocation via GET /<api-endpoint>/<allocation-id> and reports it.
#
# Group handling:
#   Pass -g/-x when the caller already knows which groups it asked for (859 requests a fixed
#   consensus/auxiliary pair); reported keys are then prefixed "cn"/"aux" so downstream workflow
#   outputs stay stable. Omit both when reading back an allocation the caller did not build (225
#   releases an arbitrary allocation id); the groups are then discovered from the response and
#   each group's own name is used as its key prefix.
#
# The double-base64 encoded JWT is read from the CHEWIE_JWT_B64 environment variable rather than
# an option so it is not exposed in the process list.
parse_chewie_allocation() {
  local HOST=""
  local API_ENDPOINT="api/v1/compute/allocation"
  local ALLOCATION_ID=0
  local CN_GROUP_NAME=""
  local AUX_GROUP_NAME=""
  local FORMAT="outputs"
  while getopts "o:e:i:g:x:f:" arg; do
    case $arg in
      o) HOST="${OPTARG}" ;;
      e) API_ENDPOINT="${OPTARG}" ;;
      i) ALLOCATION_ID=${OPTARG} ;;
      g) CN_GROUP_NAME="${OPTARG}" ;;
      x) AUX_GROUP_NAME="${OPTARG}" ;;
      f) FORMAT="${OPTARG}" ;;
      *)
        echo "Error: Invalid option" >&2
        echo "Usage: ${0} -o <host> -i <allocation_id> [-e <api_endpoint>] [-g <cn_group_name>] [-x <aux_group_name>] [-f outputs|summary|both]" >&2
        return 1
        ;;
    esac
  done

  if [[ -z "${HOST}" ]] || [[ "${ALLOCATION_ID}" == "0" ]]; then
    echo "Error: Missing required options" >&2
    return 1
  fi

  case "${FORMAT}" in
    outputs|summary|both) ;;
    *)
      echo "Error: Invalid format '${FORMAT}'. Expected one of: outputs, summary, both" >&2
      return 1
      ;;
  esac

  local CHEWIE_JWT
  CHEWIE_JWT=$("${SCRIPT_DIR}/decode-b64-jwt.sh" "${CHEWIE_JWT_B64:-}")
  if [[ "${CHEWIE_JWT}" == Error* ]]; then
    echo "Failed to decode Chewie JWT: ${CHEWIE_JWT}" >&2
    return 1
  fi

  # Log masking is exact-string: the caller's double-base64 form being a registered secret does
  # not cover the decoded token, which is a different string. Register it before it is used.
  # Guarded so a local run does not print the token via the mask command itself.
  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    echo "::add-mask::${CHEWIE_JWT}"
  fi

  local response_and_code
  response_and_code=$(curl -sS -X GET "${HOST}/${API_ENDPOINT}/${ALLOCATION_ID}" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${CHEWIE_JWT}" \
    -w '\n%{http_code}')

  local status_code="${response_and_code##*$'\n'}"
  local response="${response_and_code%$'\n'*}"

  if [[ "${status_code}" != "200" ]]; then
    echo "Failed to retrieve Chewie allocation. Status code: ${status_code}" >&2
    echo "Response: ${response}" >&2
    return 1
  fi

  echo "::group::Chewie Allocation Details"
  echo "${response}"
  echo "::endgroup::"

  local github_output="${GITHUB_OUTPUT:-/dev/null}"
  local step_summary="${GITHUB_STEP_SUMMARY:-/dev/null}"

  # Allocation-wide fields.
  local STATUS NAMESPACE CLUSTER_FQDN EXPIRATION NETWORK_ID OWNER
  STATUS=$(jq -r '.status' <<< "${response}")
  NAMESPACE=$(jq -r '.namespace' <<< "${response}")
  CLUSTER_FQDN=$(jq -r '.cluster_fqdn' <<< "${response}")
  EXPIRATION=$(jq -r '.expires_at' <<< "${response}")

  # Owner and network-id are allocation-wide but only ever appear in labels. When the caller named
  # a consensus group, read them from it; otherwise take them from the first group that carries them.
  if [[ -n "${CN_GROUP_NAME}" ]]; then
    NETWORK_ID=$(jq -r --arg g "${CN_GROUP_NAME}" '.instances[] | select(.group==$g) | .labels."solo.hashgraph.io/network-id"' <<< "${response}")
    OWNER=$(jq -r --arg g "${CN_GROUP_NAME}" '.instances[] | select(.group==$g) | .labels."solo.hashgraph.io/owner"' <<< "${response}")
  else
    NETWORK_ID=$(jq -r '[.instances[].labels."solo.hashgraph.io/network-id" // empty] | first // "unknown"' <<< "${response}")
    OWNER=$(jq -r '[.instances[].labels."solo.hashgraph.io/owner" // empty] | first // "unknown"' <<< "${response}")
  fi

  if [[ "${FORMAT}" == "outputs" || "${FORMAT}" == "both" ]]; then
    {
      echo "status=${STATUS}"
      echo "namespace=${NAMESPACE}"
      echo "kubernetes-fqdn=${CLUSTER_FQDN}"
      echo "network-id=${NETWORK_ID}"
      echo "owner=${OWNER}"
      echo "expiration=${EXPIRATION}"
    } >> "${github_output}"
  fi

  if [[ "${FORMAT}" == "summary" || "${FORMAT}" == "both" ]]; then
    {
      echo "## Chewie Allocation Details"
      echo "- Allocation ID: \`${ALLOCATION_ID}\`"
      echo "- Status: \`${STATUS}\`"
      echo "- Namespace: \`${NAMESPACE}\`"
      echo "- Cluster FQDN: \`${CLUSTER_FQDN}\`"
      echo "- Network ID: \`${NETWORK_ID}\`"
      echo "- Owner: \`${OWNER}\`"
      echo "- Expiration: \`${EXPIRATION}\`"
    } >> "${step_summary}"
  fi

  # Build the list of groups to report as "<key-prefix>:<group-name>" pairs.
  local -a groups=()
  if [[ -n "${CN_GROUP_NAME}" ]]; then
    groups+=("cn:${CN_GROUP_NAME}")
  fi
  if [[ -n "${AUX_GROUP_NAME}" ]]; then
    groups+=("aux:${AUX_GROUP_NAME}")
  fi
  if [[ ${#groups[@]} -eq 0 ]]; then
    local discovered
    while IFS= read -r discovered; do
      groups+=("${discovered}:${discovered}")
    done < <(jq -r '.instances[].group' <<< "${response}")
  fi

  local entry prefix group QTY LABELS TOLERATIONS ROLE
  for entry in "${groups[@]}"; do
    prefix="${entry%%:*}"
    group="${entry#*:}"

    QTY=$(jq -r --arg g "${group}" '.instances[] | select(.group==$g) | .spec.quantity' <<< "${response}")
    LABELS=$(jq -c --arg g "${group}" '.instances[] | select(.group==$g) | .labels' <<< "${response}")
    TOLERATIONS=$(jq -c --arg g "${group}" '.instances[] | select(.group==$g) | .tolerations' <<< "${response}")
    ROLE=$(jq -r '."solo.hashgraph.io/role" // "unknown"' <<< "${LABELS}")

    if [[ "${FORMAT}" == "outputs" || "${FORMAT}" == "both" ]]; then
      {
        echo "${prefix}-group-name=${group}"
        echo "${prefix}-quantity=${QTY}"
        echo "${prefix}-role=${ROLE}"
        echo "${prefix}-tolerations=${TOLERATIONS}"
        echo "${prefix}-labels=${LABELS}"
      } >> "${github_output}"
    fi

    if [[ "${FORMAT}" == "summary" || "${FORMAT}" == "both" ]]; then
      {
        echo "### Instance Group: \`${group}\`"
        echo "- Quantity: \`${QTY}\`"
        echo "- Role: \`${ROLE}\`"
        echo "- Tolerations: \`${TOLERATIONS}\`"
        echo "- Labels: \`${LABELS}\`"
      } >> "${step_summary}"
    fi
  done
}

parse_chewie_allocation "$@"