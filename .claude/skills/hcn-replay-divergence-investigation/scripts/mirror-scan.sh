#!/usr/bin/env bash
# List an account's transactions in a consensus-time window from the public mirror node, flagging failures.
# Shows production's side of a divergence (e.g. which transactions moved an account's balance).
#
# Usage: mirror-scan.sh <mainnet|testnet|previewnet> <0.0.account> <from-epoch-sec> <to-epoch-sec> [--all]
#   prints non-SUCCESS transactions and a count; --all prints every transaction with its transfers.
# Consensus times of the window: the block/round headers (tx-dump) or BlockStreamInfo.blockTime of the snapshots.
# Uses curl for HTTP (python's TLS client was reset in some environments) and python3 only to parse JSON.
# Compatible with bash 3.2 (stock macOS).
set -euo pipefail

network="${1:?usage: mirror-scan.sh <network> <0.0.account> <from> <to> [--all]}"
account="${2:?account}"
from="${3:?from-epoch-sec}"
to="${4:?to-epoch-sec}"
all="${5:-}"

case "${network}" in
    mainnet) base="https://mainnet-public.mirrornode.hedera.com" ;;
    testnet) base="https://testnet.mirrornode.hedera.com" ;;
    previewnet) base="https://previewnet.mirrornode.hedera.com" ;;
    *) echo "unknown network: ${network}" >&2; exit 2 ;;
esac

# python3 on macOS/Linux; on Windows Git Bash `python3` may be a Store stub that doesn't run, so fall back to `python`.
if python3 -c '' >/dev/null 2>&1; then py=python3; else py=python; fi

tmp=$(mktemp -d)
trap 'rm -rf "${tmp}"' EXIT

cat > "${tmp}/page.py" <<'EOF'
import json, sys
page = json.load(open(sys.argv[1]))
with open(sys.argv[2], "a") as out:
    for t in page.get("transactions", []):
        out.write(json.dumps(t) + "\n")
print(page.get("links", {}).get("next") or "")
EOF

cat > "${tmp}/report.py" <<'EOF'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1])]
account, show_all = sys.argv[2], sys.argv[3] == "--all"
bad = [t for t in rows if t["result"] != "SUCCESS"]
for t in (rows if show_all else bad):
    transfers = [(x["account"], x["amount"]) for x in t.get("transfers", [])]
    print(t["consensus_timestamp"], t["name"], t["result"], t["transaction_id"],
          "fee", t["charged_tx_fee"], transfers)
print("== %s: %d transaction(s) in window, %d non-SUCCESS" % (account, len(rows), len(bad)))
EOF

: > "${tmp}/all.jsonl"
next="/api/v1/transactions?account.id=${account}&timestamp=gte:${from}&timestamp=lte:${to}&limit=100&order=asc"
while [ -n "${next}" ]; do
    curl -sf -A "hedera-state-validator-investigation" "${base}${next}" > "${tmp}/page.json"
    next=$("${py}" -I "${tmp}/page.py" "${tmp}/page.json" "${tmp}/all.jsonl")
done

"${py}" -I "${tmp}/report.py" "${tmp}/all.jsonl" "${account}" "${all}"
