#!/usr/bin/env bash
# Generate a CLPR channel-identity JSON from a caller-supplied channelId
# and ECDSA secp256k1 private key. Output matches the shape produced by
# `yahcli clpr generate-channel-identity` so the file is a drop-in for
# `register-channel` / `complete-channel`.
#
# Requires: cast (Foundry) on PATH.
#
# Usage:
#   gen-channel-identity.sh --channel-id <0xhex32> --priv <0xhex32> [--out <file>]

set -euo pipefail

usage() {
    cat >&2 <<'EOF'
Usage: gen-channel-identity.sh --channel-id <0xhex32> --priv <0xhex32> [--out <file>]

  --channel-id   32-byte hex (with or without 0x prefix) — the channel id.
  --priv      32-byte hex — the secp256k1 private key.
  --out       Output file path (default: stdout).
EOF
    exit 1
}

CHANNEL_ID=""
PRIV=""
OUT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --channel-id) CHANNEL_ID="${2:-}"; shift 2 ;;
        --priv)    PRIV="${2:-}";    shift 2 ;;
        --out)     OUT="${2:-}";     shift 2 ;;
        -h|--help) usage ;;
        *) echo "Unknown arg: $1" >&2; usage ;;
    esac
done

[ -n "$CHANNEL_ID" ] && [ -n "$PRIV" ] || usage

command -v cast >/dev/null 2>&1 || {
    echo "cast not found on PATH — install Foundry: https://book.getfoundry.sh/getting-started/installation" >&2
    exit 1
}

# Strip any 0x prefix and lowercase (bash 3.2-compatible — macOS default).
norm_hex() { printf '%s' "${1#0x}" | tr '[:upper:]' '[:lower:]'; }
CHANNEL_HEX="$(norm_hex "$CHANNEL_ID")"
PRIV_HEX="$(norm_hex "$PRIV")"

[ ${#CHANNEL_HEX} -eq 64 ] || { echo "channelId must be 32 bytes (64 hex chars), got ${#CHANNEL_HEX}" >&2; exit 1; }
[ ${#PRIV_HEX} -eq 64 ] || { echo "privateKey must be 32 bytes (64 hex chars), got ${#PRIV_HEX}" >&2; exit 1; }

# 1. Derive uncompressed public key — cast returns X||Y (64 bytes, no 0x04 prefix).
PUB_HEX="$(cast wallet public-key --private-key "0x${PRIV_HEX}")"
PUB_HEX="${PUB_HEX#0x}"

# 2. ownershipCommitment = keccak256(channelId || publicKey)
COMMIT="$(cast keccak "0x${CHANNEL_HEX}${PUB_HEX}")"
COMMIT="${COMMIT#0x}"

# 3. signature = ECDSA sign(keccak256(channelId), privateKey).
#    cast returns 65 bytes (r||s||v); the on-ledger format is the raw r||s — drop v.
MSG_HASH="$(cast keccak "0x${CHANNEL_HEX}")"
SIG="$(cast wallet sign --no-hash --private-key "0x${PRIV_HEX}" "$MSG_HASH")"
SIG="${SIG#0x}"
SIG_RS="${SIG:0:128}"

JSON="$(cat <<EOF
{
  "channelId":       "0x${CHANNEL_HEX}",
  "publicKey":          "0x${PUB_HEX}",
  "privateKey":         "0x${PRIV_HEX}",
  "signatureScheme":    "ECDSA_SECP256K1",
  "ownershipCommitment":"0x${COMMIT}",
  "signature":          "0x${SIG_RS}"
}
EOF
)"

if [ -n "$OUT" ]; then
    printf '%s\n' "$JSON" > "$OUT"
    echo "Wrote channel identity bundle to $OUT" >&2
else
    printf '%s\n' "$JSON"
fi
