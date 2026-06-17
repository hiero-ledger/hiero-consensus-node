#!/usr/bin/env bash
#
# genesisNetworkBNRSARoster.sh
#
# Convert a hiero-consensus-node genesis-network.json into the
# hiero-block-node rsa-bootstrap-roster.json shape.
#
#   Input  (CN):  nodeMetadata[].rosterEntry.{nodeId, gossipCaCertificate}
#                 where gossipCaCertificate is a base64-encoded X.509 DER cert.
#
#   Output (BN): nodeAddress[].{RSAPubKey, nodeId}
#                 where RSAPubKey is the lowercase-hex SubjectPublicKeyInfo
#                 (PKIX) DER bytes of the cert's public key.
#
# The script makes no assumptions about its own location or the current
# working directory. The caller passes the absolute path to the
# genesis-network.json via -i; the output path (optional) goes via -o.

set -euo pipefail
# Locale-fixed for deterministic byte handling in tr/sort/openssl.
export LC_ALL=C

usage() {
  cat <<'EOF'
Usage: genesisNetworkBNRSARoster.sh -i <input> [-o <output>] [-h]

Converts a CN genesis-network.json into a BN rsa-bootstrap-roster.json.

Options:
  -i <input>   Absolute path to the genesis-network.json file (required).
  -o <output>  Path to write rsa-bootstrap-roster.json. Default: stdout.
  -h           Show this help.

Required tools: jq, openssl, xxd, base64.
EOF
}

# --- arg parsing ------------------------------------------------------------

INPUT=""
OUTPUT=""

while getopts ":i:o:h" opt; do
  case "$opt" in
    i) INPUT="$OPTARG" ;;
    o) OUTPUT="$OPTARG" ;;
    h) usage; exit 0 ;;
    :) echo "Error: -$OPTARG requires an argument" >&2; usage >&2; exit 2 ;;
    \?) echo "Error: unknown option -$OPTARG" >&2; usage >&2; exit 2 ;;
  esac
done
shift $((OPTIND - 1))
if [[ $# -gt 0 ]]; then
  echo "Error: unexpected positional argument(s): $*" >&2
  usage >&2
  exit 2
fi

if [[ -z "$INPUT" ]]; then
  echo "Error: -i <input> is required (absolute path to genesis-network.json)" >&2
  usage >&2
  exit 2
fi

if [[ "$INPUT" != /* ]]; then
  echo "Error: -i must be an absolute path, got: $INPUT" >&2
  exit 2
fi
if [[ -n "$OUTPUT" && "$OUTPUT" != /* ]]; then
  echo "Error: -o must be an absolute path, got: $OUTPUT" >&2
  exit 2
fi

# --- pre-flight checks ------------------------------------------------------

for bin in jq openssl xxd base64; do
  if ! command -v "$bin" >/dev/null 2>&1; then
    echo "Error: required tool '$bin' not found in PATH" >&2
    exit 1
  fi
done

if [[ ! -r "$INPUT" ]]; then
  echo "Error: cannot read input file: $INPUT" >&2
  exit 1
fi

if ! jq -e . "$INPUT" >/dev/null 2>&1; then
  echo "Error: $INPUT is not valid JSON" >&2
  exit 1
fi

node_count="$(jq '.nodeMetadata | if type=="array" then length else -1 end' "$INPUT")"
if [[ "$node_count" -lt 0 ]]; then
  echo "Error: $INPUT does not contain a 'nodeMetadata' array" >&2
  exit 1
fi
if [[ "$node_count" -eq 0 ]]; then
  echo "Error: 'nodeMetadata' array in $INPUT is empty" >&2
  exit 1
fi

# --- output staging ---------------------------------------------------------

# Stage output to a temp file and atomically move on success so a partial
# failure never leaves a half-written file in place.
tmp_out=""
cleanup() {
  if [[ -n "$tmp_out" && -f "$tmp_out" ]]; then rm -f "$tmp_out"; fi
  return 0  # ensure EXIT trap never propagates a non-zero status
}
trap cleanup EXIT

if [[ -n "$OUTPUT" ]]; then
  out_dir="$(dirname "$OUTPUT")"
  if [[ ! -d "$out_dir" ]]; then
    echo "Error: output directory does not exist: $out_dir" >&2
    exit 1
  fi
  tmp_out="$(mktemp "${OUTPUT}.XXXXXX")"
fi

# --- conversion -------------------------------------------------------------

# Extract (nodeId, base64 gossipCaCertificate) pairs, TAB-separated.
# `tojson` on the cert is safer than raw selection (no risk of embedded tabs
# from the b64 alphabet — which can't contain tabs — but kept defensive).
pairs="$(jq -r '
  .nodeMetadata[]
  | .rosterEntry
  | [ (.nodeId|tostring), .gossipCaCertificate ]
  | @tsv
' "$INPUT")"

# RSA encryption OID prefix in DER SubjectPublicKeyInfo.
# SEQUENCE { SEQUENCE { OID 1.2.840.113549.1.1.1, NULL }, BIT STRING { ... } }
# The first ~30 bytes are fixed for an RSA SPKI; the OID itself appears at:
#   06 09 2a 86 48 86 f7 0d 01 01 01
RSA_OID_HEX="06092a864886f70d010101"

entries_json="[]"
idx=0
while IFS=$'\t' read -r node_id cert_b64; do
  [[ -z "$node_id" ]] && continue
  idx=$((idx + 1))

  if [[ -z "$cert_b64" || "$cert_b64" == "null" ]]; then
    echo "Error: nodeMetadata[$((idx-1))] (nodeId=$node_id) has empty gossipCaCertificate" >&2
    exit 1
  fi

  # base64 cert -> X.509 DER -> PEM SPKI -> DER SPKI -> lowercase hex
  if ! pubkey_hex="$(
    printf '%s' "$cert_b64" \
      | base64 -d 2>/dev/null \
      | openssl x509 -inform DER -pubkey -noout 2>/dev/null \
      | openssl pkey -pubin -outform DER 2>/dev/null \
      | xxd -p \
      | tr -d '\n'
  )"; then
    echo "Error: openssl pipeline failed for nodeMetadata[$((idx-1))] (nodeId=$node_id)" >&2
    exit 1
  fi

  if [[ -z "$pubkey_hex" ]]; then
    echo "Error: empty public key for nodeMetadata[$((idx-1))] (nodeId=$node_id)" >&2
    exit 1
  fi

  # Sanity check: confirm it's an RSA SPKI by looking for the OID.
  if [[ "$pubkey_hex" != *"$RSA_OID_HEX"* ]]; then
    echo "Error: nodeId=$node_id public key is not RSA (rsaEncryption OID not found in SPKI)" >&2
    exit 1
  fi

  entries_json="$(jq -c \
      --arg pk "$pubkey_hex" \
      --argjson id "$node_id" \
      '. + [{RSAPubKey: $pk, nodeId: $id}]' \
      <<<"$entries_json")"
done < <(printf '%s\n' "$pairs")

# Sort by nodeId ascending for stable, input-order-independent output.
result="$(jq -n --argjson entries "$entries_json" \
  '{nodeAddress: ($entries | sort_by(.nodeId))}')"

# --- write ------------------------------------------------------------------

if [[ -n "$OUTPUT" ]]; then
  printf '%s\n' "$result" > "$tmp_out"
  mv -f "$tmp_out" "$OUTPUT"
  tmp_out=""  # disarm cleanup; mv already consumed it
  echo "Wrote $(jq '.nodeAddress | length' <<<"$result") entries to $OUTPUT" >&2
else
  printf '%s\n' "$result"
fi
