#!/usr/bin/env bash
# Generate a CLPR channel-identity JSON from a caller-supplied channelId and an ECDSA
# secp256k1 private key held in a file. Output matches the shape produced by
# `yahcli clpr generate-channel-identity` so the file is a drop-in for
# `register-channel` / `complete-channel`.
#
# The private key is read from --key-file and handed to hedera-node/tools/sign-clpr-channel-identity.py
# by file path, so it never appears on any process command line. The bundle itself never contains
# the key; with --out, the key is also saved (mode 0600) to <out>.key. Existing files are moved to
# <name>.<epoch>.bak (0600), never overwritten or deleted.
#
# Requires: python3 with pycryptodome and coincurve (pip install pycryptodome coincurve).
#
# Usage:
#   gen-channel-identity.sh --channel-id <0xhex32> --key-file <path> [--out <file>]
#   (set TOOLS_DIR to override where sign-clpr-channel-identity.py is looked up)

set -euo pipefail

usage() {
    cat >&2 <<'EOF2'
Usage: gen-channel-identity.sh --channel-id <0xhex32> --key-file <path> [--out <file>]

  --channel-id   32-byte hex (with or without 0x prefix) — the channel id.
  --key-file     File containing the 32-byte hex secp256k1 private key (keep it mode 0600).
  --out          Output file path (default: stdout). The key is also written to <out>.key.
  --allow-loose-key-file
                 Accept a key file that group/others can access or that you do not own
                 (symlinks and non-regular files are always refused).
EOF2
    exit 1
}

CHANNEL_ID=""
KEY_FILE=""
OUT=""
ALLOW_LOOSE=0
while [ $# -gt 0 ]; do
    case "$1" in
        --channel-id) CHANNEL_ID="${2:-}"; shift 2 ;;
        --key-file)   KEY_FILE="${2:-}";   shift 2 ;;
        --out)        OUT="${2:-}";        shift 2 ;;
        --allow-loose-key-file) ALLOW_LOOSE=1; shift ;;
        -h|--help)    usage ;;
        --priv)       echo "--priv is no longer supported: a key on the command line is visible to other processes. Put the key in a file and use --key-file." >&2; exit 1 ;;
        *) echo "Unknown arg: $1" >&2; usage ;;
    esac
done

[ -n "$CHANNEL_ID" ] && [ -n "$KEY_FILE" ] || usage
[ -f "$KEY_FILE" ] || { echo "key file not found: $KEY_FILE" >&2; exit 1; }

# Locate the signer.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SIGNER=""
for dir in "${TOOLS_DIR:-}" "${SCRIPT_DIR}/../../../tools" "${SCRIPT_DIR}/../tools"; do
    if [ -n "$dir" ] && [ -f "$dir/sign-clpr-channel-identity.py" ]; then
        SIGNER="$dir/sign-clpr-channel-identity.py"
        break
    fi
done
[ -n "$SIGNER" ] || { echo "sign-clpr-channel-identity.py not found (set TOOLS_DIR)" >&2; exit 1; }

# Shared key-file helpers (this script exists in yahcli/ and in yahcli/scripts/clpre2e/).
KEY_LIB=""
for f in "${SCRIPT_DIR}/key-files.sh" "${SCRIPT_DIR}/scripts/clpre2e/key-files.sh"; do
    if [ -f "$f" ]; then KEY_LIB="$f"; break; fi
done
[ -n "$KEY_LIB" ] || { echo "key-files.sh not found next to this script" >&2; exit 1; }
# shellcheck source=key-files.sh
. "$KEY_LIB"

# Refuse a key file that is a symlink, or (unless --allow-loose-key-file) owned by someone else or
# accessible by group/others.
[ "$ALLOW_LOOSE" -eq 1 ] && export ALLOW_LOOSE_KEY_FILE=1
kf_check_key_file "$KEY_FILE" || exit 1

# Strip any 0x prefix and lowercase (bash 3.2-compatible — macOS default).
norm_hex() { printf '%s' "${1#0x}" | tr '[:upper:]' '[:lower:]'; }
CHANNEL_HEX="$(norm_hex "$CHANNEL_ID")"
PRIV_HEX="$(norm_hex "$(tr -d '[:space:]' < "$KEY_FILE")")"
[ ${#CHANNEL_HEX} -eq 64 ] || { echo "channelId must be 32 bytes (64 hex chars), got ${#CHANNEL_HEX}" >&2; exit 1; }
[ ${#PRIV_HEX} -eq 64 ] || { echo "private key must be 32 bytes (64 hex chars), got ${#PRIV_HEX}" >&2; exit 1; }

SIGNER_EXTRA=()
[ "$ALLOW_LOOSE" -eq 1 ] && SIGNER_EXTRA=(--allow-loose-key-file)

# Public-only stub (no secrets) for the signer to complete.
STUB="$(mktemp)"
trap 'rm -f "$STUB"' EXIT
printf '{"channelId":"0x%s","signatureScheme":"ECDSA_SECP256K1"}\n' "$CHANNEL_HEX" > "$STUB"

if [ -n "$OUT" ]; then
    # Check both targets before writing anything so a refusal never leaves a stray key behind.
    for target in "$OUT" "${OUT}.key"; do
        if [ -L "$target" ]; then
            echo "Refusing to write through symlink: $target" >&2
            exit 1
        fi
    done
    SAME_KEY=0
    [ "$KEY_FILE" -ef "${OUT}.key" ] && SAME_KEY=1
    # Sign first (the signer backs up and replaces $OUT atomically w.r.t. our key handling).
    python3 "$SIGNER" "$STUB" --key-file "$KEY_FILE" --out "$OUT" ${SIGNER_EXTRA[@]+"${SIGNER_EXTRA[@]}"}
    if [ "$SAME_KEY" -eq 0 ]; then
        kf_backup_existing "${OUT}.key" || exit 1
        (umask 177 && printf '0x%s\n' "${PRIV_HEX}" > "${OUT}.key")
    else
        chmod 600 "${OUT}.key"
    fi
    echo "Wrote channel identity bundle to $OUT" >&2
    echo "Wrote private key to ${OUT}.key — keep it secret" >&2
else
    python3 "$SIGNER" "$STUB" --key-file "$KEY_FILE" ${SIGNER_EXTRA[@]+"${SIGNER_EXTRA[@]}"}
    echo "WARNING: private key not saved (no --out given)" >&2
fi
