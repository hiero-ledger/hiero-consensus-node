#!/usr/bin/env bash

export NODE_ID CI_WAIT_FOR_PEERS

function wait_for_address_resolution {
  local hostname="${1}"
  local timeout="${2}"

  [[ -n "${hostname}" ]] || return 67
  [[ -n "${timeout}" ]] || timeout=0

  local start now delta
  start="$(date +%s)"
  while ! host -t A "${hostname}" >/dev/null 2>&1; do
    sleep 1
    now="$(date +%s)"
    delta="$(( now - start ))"

    if [[ "${timeout}" -gt 0 && "${delta}" -gt "${timeout}" ]]; then
      return 1
    fi
  done

  return 0
 }

function wait_for_peers {
  local nodes=()
  local line
  while IFS= read -r line; do
    nodes+=("${line}")
  done < <(grep '^address,.*' config.txt | awk -F, '{ printf "%s\n",$7 }' | tr -d '[:blank:]')

  local node
  local idx=-1
  for node in "${nodes[@]}"; do
    idx=$(( idx + 1 ))
    if [[ "${idx}" -eq "${NODE_ID}" ]]; then
        echo "Waiting for ${node} .......... SKIPPED (Self Node)"
        continue
    fi
    echo -n "Waiting for ${node} .......... "
    if ! wait_for_address_resolution "${node}" 600; then
      echo "FAILED"
      return 1
    fi

    local peerAddress
    peerAddress="$(host -t A "${node}" | awk '{ print $4 }')"
    echo "SUCCESS (Peer Online) [IP: ${peerAddress}]"
  done

  return 0
}

cp config-mount/*.properties data/config
cp config-mount/* .
sed -i "s/NODE_ID/${NODE_ID}/" settings.txt
cat settings.txt

if [[ "${CI_WAIT_FOR_PEERS}" = true && -n "${NODE_ID}" ]]; then
  echo "============================= Waiting For Peers ============================="
  if ! wait_for_peers; then
    echo "============================= Unable to Locate Peers ============================="
    exit 16
  fi
  echo "============================= Peers Found ============================="
fi

/usr/bin/env java -cp 'data/lib/*' -Dflag=1 -Dfile.encoding='utf-8' \
  -XX:+UnlockExperimentalVMOptions \
  -XX:+UseZGC \
  -XX:ZAllocationSpikeTolerance=2 \
  -XX:ConcGCThreads=14 \
  -XX:MetaspaceSize=100M \
  -XX:+ZGenerational \
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  -Dio.netty.tryReflectionSetAccessible=true \
  com.swirlds.platform.Browser
