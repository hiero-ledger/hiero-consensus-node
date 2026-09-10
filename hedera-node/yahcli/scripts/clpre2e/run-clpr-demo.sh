#!/usr/bin/env bash
#
# run-clpr-demo.sh
#
# End-to-end runbook for every yahcli command added on the add-yahcli-commands branch,
# exercised against TWO local Hiero networks ("ledger A" and "ledger B"). The script
# does NOT generate real cryptographic material -- commit/reveal payloads and signatures
# are static placeholder bytes, so the precheck path of every command runs, but the
# handler-side signature/proof checks on complete-channel / complete-connector /
# submit-bundle will (correctly) reject the placeholder values. That is expected for a
# smoke test; the value here is exercising the wire path of every command on both ledgers.
#
# Prerequisites
# -------------
#   1. Two local hedera-services nodes running locally:
#        - "ledger A" on 127.0.0.1:50211  (matches yahcli network `localhost`)
#        - "ledger B" on 127.0.0.1:50311  (matches yahcli network `localhost2`)
#      Both must accept gRPC for account 0.0.3.
#   2. yahcli.jar built in this directory (run: ../../gradlew :yahcli:copyYahCli).
#   3. config.yml in this directory listing both networks (already present).
#   4. localhost/keys/account2.{pem,pass} and localhost2/keys/account2.{pem,pass}
#      providing a funded payer on each network (already present).
#   5. ledger-config.json (for ledger A) and ledger-config-b.json (for ledger B)
#      describing each ledger's ClprLedgerConfiguration (already present).
#
# Usage
# -----
#   ./run-clpr-demo.sh             # runs every step against both networks
#   STOP_ON_FAIL=1 ./run-clpr-demo.sh   # abort at first command failure
#   STEP_GAS=200000 ./run-clpr-demo.sh  # override the contract-create gas
#
# All hex placeholders below can be overridden by exporting the same name before invoking.
#

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# yahcli launcher, config.yml, ledger-config*.json, per-network keys, and the
# bundled Multipurpose.bin all live in the yahcli/ root — two directories up.
YAHCLI_DIR="$(cd "${YAHCLI_DIR:-${SCRIPT_DIR}/../..}" && pwd)"
cd "${YAHCLI_DIR}"

YAHCLI_JAR="${YAHCLI_JAR:-${YAHCLI_DIR}/yahcli.jar}"
STOP_ON_FAIL="${STOP_ON_FAIL:-0}"

# Two networks defined in config.yml.
NET_A="${NET_A:-localhost}"
NET_B="${NET_B:-localhost2}"

# Per-ledger configuration JSON files (proto3-JSON for ClprLedgerConfiguration).
CONFIG_A="${CONFIG_A:-${YAHCLI_DIR}/ledger-config.json}"
CONFIG_B="${CONFIG_B:-${YAHCLI_DIR}/ledger-config-b.json}"

# A solc-style .bin file (hex-encoded bytecode, ASCII). Multipurpose.bin ships with
# yahcli and is used here to exercise `contracts create` without needing constructor args.
INIT_CODE_FILE="${INIT_CODE_FILE:-${YAHCLI_DIR}/src/main/resources/Multipurpose.bin}"

# Static placeholders. Override via env vars to plug in real cryptographic material.
# 32-byte hashes / ids
COMMITMENT_CHANNEL_HEX="${COMMITMENT_CHANNEL_HEX:-1111111111111111111111111111111111111111111111111111111111111111}"
COMMITMENT_CONNECTOR_HEX="${COMMITMENT_CONNECTOR_HEX:-2222222222222222222222222222222222222222222222222222222222222222}"
CHANNEL_ID_HEX="${CHANNEL_ID_HEX:-3333333333333333333333333333333333333333333333333333333333333333}"
CONNECTOR_ID_HEX="${CONNECTOR_ID_HEX:-4444444444444444444444444444444444444444444444444444444444444444}"
SALT_HEX="${SALT_HEX:-5555555555555555555555555555555555555555555555555555555555555555}"
# 32-byte ED25519 public key
PUBLIC_KEY_HEX="${PUBLIC_KEY_HEX:-6666666666666666666666666666666666666666666666666666666666666666}"
# 64-byte ED25519 signature
SIGNATURE_HEX="${SIGNATURE_HEX:-77777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777}"
# Opaque peer-ledger identifier for the bundled verifier contract.
PEER_LEDGER_ID_A="${PEER_LEDGER_ID_A:-deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef}"
PEER_LEDGER_ID_B="${PEER_LEDGER_ID_B:-feedfacefeedfacefeedfacefeedfacefeedfacefeedfacefeedfacefeedface}"
# Hex-encoded message id for the submit-bundle smoke.
BUNDLE_PAYLOAD_HEX="${BUNDLE_PAYLOAD_HEX:-deadbeef}"
# Long message id used by redact-message.
REDACT_MESSAGE_ID="${REDACT_MESSAGE_ID:-1}"
# Account that receives returned stake on deregister-connector (must also sign).
STAKE_RECIPIENT="${STAKE_RECIPIENT:-0.0.2}"
# Gas to use for contract create / call.
STEP_GAS="${STEP_GAS:-200000}"
# Gas to use for ClprLedgerVerifier deployment. The constructor stores a `bytes` arg in
# storage, so it needs more headroom than a no-arg `contracts create`.
VERIFIER_GAS="${VERIFIER_GAS:-500000}"
# Memo applied to the ClprLedgerVerifier contract at deploy time.
VERIFIER_MEMO="${VERIFIER_MEMO:-clpr-ledger-verifier}"

# ----- helpers ---------------------------------------------------------------

YELLOW='\033[1;33m'
CYAN='\033[1;36m'
GREEN='\033[1;32m'
RED='\033[1;31m'
DIM='\033[2m'
RESET='\033[0m'

print_header() {
    printf "\n${CYAN}==> %s${RESET}\n" "$*"
}

print_step() {
    printf "${YELLOW}  -- %s${RESET}\n" "$*"
}

print_ok() {
    printf "${GREEN}  ok${RESET} %s\n" "$*"
}

print_fail() {
    printf "${RED}  FAIL${RESET} %s\n" "$*"
}

# Run a yahcli invocation. First arg is a short label, rest is the command line.
# Honors STOP_ON_FAIL.
run_yahcli() {
    local label="$1"
    shift
    printf "${DIM}     \$ java -jar yahcli.jar %s${RESET}\n" "$*"
    if java -jar "${YAHCLI_JAR}" "$@"; then
        print_ok "${label}"
    else
        print_fail "${label} (exit $?)"
        if [[ "${STOP_ON_FAIL}" == "1" ]]; then
            exit 1
        fi
    fi
}

require_file() {
    local path="$1"
    local hint="$2"
    if [[ ! -f "${path}" ]]; then
        printf "${RED}Missing required file:${RESET} %s\n  hint: %s\n" "${path}" "${hint}" >&2
        exit 1
    fi
}

# ----- preflight -------------------------------------------------------------

print_header "Preflight checks"
require_file "${YAHCLI_JAR}" "build with: (cd ${REPO_ROOT:-../../../..} && ./gradlew :yahcli:copyYahCli)"
require_file "${YAHCLI_DIR}/config.yml" "config.yml must list networks '${NET_A}' and '${NET_B}'"
require_file "${CONFIG_A}" "ledger A ClprLedgerConfiguration JSON"
require_file "${CONFIG_B}" "ledger B ClprLedgerConfiguration JSON"
require_file "${INIT_CODE_FILE}" "solc-style hex bytecode (.bin)"
require_file "${YAHCLI_DIR}/${NET_A}/keys/account2.pem" "payer key for ${NET_A}"
require_file "${YAHCLI_DIR}/${NET_B}/keys/account2.pem" "payer key for ${NET_B}"
print_ok "all prerequisites present"

# ----- per-network demo ------------------------------------------------------

# Runs the full flow against a single network. Args: <network-id> <ledger-config-json> <peer-ledger-id-hex>
run_on_network() {
    local net="$1"
    local cfg="$2"
    local peer_ledger_id="$3"

    print_header "Network ${net} (ledger configuration: $(basename "${cfg}"))"

    # 1) Push the ledger's own ClprLedgerConfiguration.
    print_step "[1/12] update-ledger-configuration"
    run_yahcli "update-ledger-configuration on ${net}" \
        -n "${net}" clpr update-ledger-configuration --config-file "${cfg}"

    # 2) Read it back to confirm it landed.
    print_step "[2/12] get-ledger-configuration"
    run_yahcli "get-ledger-configuration on ${net}" \
        -n "${net}" clpr get-ledger-configuration --json --include-defaults

    # 3) Phase 1 of the two-phase commit/reveal for a channel.
    print_step "[3/12] register-channel (commit)"
    run_yahcli "register-channel on ${net}" \
        -n "${net}" clpr register-channel --commitment "${COMMITMENT_CHANNEL_HEX}"

    # 4) Deploy the verifier first so we can pass its id into complete-channel.
    #    --immutable: no admin key, so peers can trust the verifier won't be swapped out.
    #    --memo: shows up in mirror-node lookups; useful when several verifiers coexist.
    print_step "[4/12] contracts deploy-clpr-verifier"
    run_yahcli "deploy-clpr-verifier on ${net}" \
        -n "${net}" contracts deploy-clpr-verifier \
            --ledger-id "${peer_ledger_id}" \
            --gas "${VERIFIER_GAS}" \
            --memo "${VERIFIER_MEMO}" \
            --immutable

    # 5) Phase 2 reveal. Signature/public-key are placeholders so this will be rejected by
    #    the handler; that's fine for a smoke test.
    print_step "[5/12] complete-channel (reveal, placeholder signature)"
    run_yahcli "complete-channel on ${net}" \
        -n "${net}" clpr complete-channel \
            --channel-id "${CHANNEL_ID_HEX}" \
            --public-key "${PUBLIC_KEY_HEX}" \
            --signature "${SIGNATURE_HEX}" \
            --signature-scheme ED25519

    # 6) Phase 1 of the connector commit/reveal.
    print_step "[6/12] register-connector (commit)"
    run_yahcli "register-connector on ${net}" \
        -n "${net}" clpr register-connector --commitment "${COMMITMENT_CONNECTOR_HEX}"

    # 7) Phase 2 reveal for the connector.
    print_step "[7/12] complete-connector (reveal, placeholder signature)"
    run_yahcli "complete-connector on ${net}" \
        -n "${net}" clpr complete-connector \
            --connector-id "${CONNECTOR_ID_HEX}" \
            --public-key "${PUBLIC_KEY_HEX}" \
            --signature "${SIGNATURE_HEX}" \
            --signature-scheme ED25519 \
            --salt "${SALT_HEX}" \
            --channel-id "${CHANNEL_ID_HEX}" \
            --locked-stake 0

    # 8) Generic contract create (Multipurpose.bin, no constructor args).
    print_step "[8/12] contracts create (Multipurpose.bin)"
    run_yahcli "contracts create on ${net}" \
        -n "${net}" contracts create \
            --init-code-file "${INIT_CODE_FILE}" \
            --gas "${STEP_GAS}" \
            --immutable

    # 9) Call the verifier with empty calldata (the EVM-level fallback path) just to
    #    exercise `contracts call`. The contract has no fallback, so this is expected
    #    to revert -- precheck still runs.
    print_step "[9/12] contracts call (empty calldata, fallback)"
    run_yahcli "contracts call on ${net}" \
        -n "${net}" contracts call \
            --contract-id "0.0.1010" \
            --gas "${STEP_GAS}"

    # 10) Submit a placeholder bundle. Will fail handler validation (no real connector).
    print_step "[10/12] submit-bundle (placeholder payload)"
    run_yahcli "submit-bundle on ${net}" \
        -n "${net}" clpr submit-bundle \
            --channel-id "${CHANNEL_ID_HEX}" \
            --bundle-payload "${BUNDLE_PAYLOAD_HEX}" \
            --endpoint-node-id 0

    # 11) Redact a placeholder message.
    print_step "[11/12] redact-message"
    run_yahcli "redact-message on ${net}" \
        -n "${net}" clpr redact-message \
            --channel-id "${CHANNEL_ID_HEX}" \
            --message-id "${REDACT_MESSAGE_ID}"

    # 12) Teardown: deregister the connector then close the channel.
    print_step "[12/12] deregister-connector and close-channel"
    run_yahcli "deregister-connector on ${net}" \
        -n "${net}" clpr deregister-connector \
            --channel-id "${CHANNEL_ID_HEX}" \
            --connector-id "${CONNECTOR_ID_HEX}" \
            --stake-recipient "${STAKE_RECIPIENT}"
    run_yahcli "close-channel on ${net}" \
        -n "${net}" clpr close-channel --channel-id "${CHANNEL_ID_HEX}"
}

# ----- drive both networks ---------------------------------------------------

run_on_network "${NET_A}" "${CONFIG_A}" "${PEER_LEDGER_ID_A}"
run_on_network "${NET_B}" "${CONFIG_B}" "${PEER_LEDGER_ID_B}"

print_header "Done"
echo "Each step above prints the precheck and consensus status reported by yahcli."
echo "Steps using placeholder signatures/proofs will report a handler-level rejection,"
echo "but the precheck and gRPC paths were exercised on both ${NET_A} and ${NET_B}."
