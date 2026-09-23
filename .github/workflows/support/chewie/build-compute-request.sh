#!/usr/bin/env bash
set -e

build_compute_request() {
  local CONSENSUS_GROUP_NAME=""
  local AUXILIARY_GROUP_NAME=""
  local CN_QUANTITY=0
  local AUX_QUANTITY=0
  local CN_CPU=0
  local AUX_CPU=0
  local CN_MEMORY=0
  local AUX_MEMORY=0
  local DURATION=0
  local RUN_ID=0
  local RUN_NUMBER=0
  local RUN_ATTEMPT=0
  local OWNER=""
  local REPOSITORY=""
  local JOB=""
  local REQUEST_TIMEOUT=0
  while getopts "d:q:c:m:g:a:p:w:x:i:n:t:o:r:j:e:" arg; do
    case $arg in
      d) DURATION=${OPTARG} ;;
      q) CN_QUANTITY=${OPTARG} ;;
      c) CN_CPU=${OPTARG} ;;
      m) CN_MEMORY=${OPTARG} ;;
      g) CONSENSUS_GROUP_NAME="${OPTARG}" ;;
      a) AUX_QUANTITY=${OPTARG} ;;
      p) AUX_CPU=${OPTARG} ;;
      w) AUX_MEMORY=${OPTARG} ;;
      x) AUXILIARY_GROUP_NAME="${OPTARG}" ;;
      i) RUN_ID=${OPTARG} ;;
      n) RUN_NUMBER=${OPTARG} ;;
      t) RUN_ATTEMPT=${OPTARG} ;;
      o) OWNER="${OPTARG}" ;;
      r) REPOSITORY="${OPTARG}" ;;
      j) JOB="${OPTARG}" ;;
      e) REQUEST_TIMEOUT=${OPTARG} ;;
      *)
        echo "Error: Invalid option" >&2
        echo "Usage: ${0} -q <cn_quantity> -c <cn_cpu> -m <cn_memory> -d <duration> -g <consensus-group-name> -a <aux_quantity> -p <aux_cpu> -w <aux_memory> -x <auxiliary-group-name> -i <run_id> -n <run_number> -t <run_attempt> -o <owner> -r <repository> -j <job> -e <request_timeout>" >&2
        return 1
        ;;
    esac
  done

  # validate that all required options are provided
  if [[ "$CN_QUANTITY" == "0" ]] || [[ "$CN_CPU" == "0" ]] || [[ "$CN_MEMORY" == "0" ]] || \
     [[ "$AUX_QUANTITY" == "0" ]] || [[ "$AUX_CPU" == "0" ]] || [[ "$AUX_MEMORY" == "0" ]] || \
     [[ "$DURATION" == "0" ]] || [[ "$RUN_ID" == "0" ]] || [[ "$RUN_NUMBER" == "0" ]] || \
     [[ "$RUN_ATTEMPT" == "0" ]] || [[ -z "$OWNER" ]] || \
     [[ -z "$REPOSITORY" ]] || [[ -z "$JOB" ]]; then
    echo "Error: Missing required options"
    return 1
  fi

  if [[ "${CONSENSUS_GROUP_NAME:-}" == "" ]]; then
    CONSENSUS_GROUP_NAME="cn-nodes"
  fi

  if [[ "${AUXILIARY_GROUP_NAME:-}" == "" ]]; then
    AUXILIARY_GROUP_NAME="aux-nodes"
  fi

  if [[ "${REQUEST_TIMEOUT:-}" == "0" ]]; then
    REQUEST_TIMEOUT=3600
  fi

  # build curl body into a variable
  local REQUEST_BODY
  REQUEST_BODY=$(cat <<EOF
{
  "instances":[
    {
      "group": "${CONSENSUS_GROUP_NAME}",
      "quantity":${CN_QUANTITY},
      "resources":{"cpu":${CN_CPU},"memory":${CN_MEMORY}}
    },
    {
      "group": "${AUXILIARY_GROUP_NAME}",
      "quantity":${AUX_QUANTITY},
      "resources":{"cpu":${AUX_CPU},"memory":${AUX_MEMORY}}
    }
  ],
  "duration":${DURATION},
  "request_timeout": ${REQUEST_TIMEOUT},
  "workflow":{
    "run":{
      "id":${RUN_ID},
      "number":${RUN_NUMBER},
      "attempt":${RUN_ATTEMPT}
    },
    "owner":"${OWNER}",
    "repository":"${REPOSITORY}",
    "job":"${JOB}"
  }
}
EOF
)

  REQUEST_BODY=$(echo "${REQUEST_BODY}" | jq -c .)
  echo "${REQUEST_BODY}"
}

build_compute_request "$@"
