#!/usr/bin/env sh
#
# Wrapper around Sei localnode genesis generation that pre-funds EVM deployer
# accounts needed by the local CLPR scripts.
set -eu

ORIGINAL_GENESIS_SCRIPT="${SEI_ORIGINAL_GENESIS_SCRIPT:-/sei-protocol/sei-chain/docker/localnode/scripts/step2_genesis.sh}"
PREFUND_ACCOUNTS="${SEI_PREFUND_ACCOUNTS:-}"
PREFUND_AMOUNT="${SEI_PREFUND_AMOUNT:-1000000000000000000000}"

"${ORIGINAL_GENESIS_SCRIPT}"

if [ -z "${PREFUND_ACCOUNTS}" ]; then
  exit 0
fi

to_sei_address() {
  addr="$1"
  case "${addr}" in
    0x*|0X*)
      hex="${addr#0x}"
      hex="${hex#0X}"
      seid keys parse "${hex}" | awk '/^- sei1/ { print $2; exit }'
      ;;
    sei1*)
      echo "${addr}"
      ;;
    *)
      echo "unsupported SEI_PREFUND_ACCOUNTS entry: ${addr}" >&2
      return 1
      ;;
  esac
}

contains_account() {
  sei_addr="$1"
  jq -e --arg addr "${sei_addr}" \
    '.app_state.bank.balances[]? | select(.address == $addr)' \
    "$HOME/.sei/config/genesis.json" >/dev/null
}

for account in $(printf '%s' "${PREFUND_ACCOUNTS}" | tr ',' ' '); do
  if [ -z "${account}" ]; then
    continue
  fi
  sei_addr="$(to_sei_address "${account}")"
  if [ -z "${sei_addr}" ]; then
    echo "failed to convert ${account} to a Sei address" >&2
    exit 1
  fi
  if contains_account "${sei_addr}"; then
    echo "Prefunded account already present: ${sei_addr}"
    continue
  fi
  echo "Adding prefunded local EVM account: ${sei_addr}"
  seid add-genesis-account "${sei_addr}" \
    "${PREFUND_AMOUNT}usei,${PREFUND_AMOUNT}uusdc,${PREFUND_AMOUNT}uatom"
done

cp "$HOME/.sei/config/genesis.json" build/generated/genesis.json
