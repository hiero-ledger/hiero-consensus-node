#!/usr/bin/env python3
"""Field-level comparison of the two diff files written by `diff` (state1-diff.json / state2-diff.json).

Usage: python3 diff-fields.py state1-diff.json state2-diff.json [--show-hashes]

state1 is the expected (target) state, state2 the candidate (replayed or applied) state. Records are matched by
(path, key). For each record the differing leaf fields are printed with both values; long hash-like values are
summarized unless --show-hashes is given. Numeric differences are shown as state2 - state1.

The diff files only carry a numeric Merkle path `p`, so each record is labelled with the state it most likely
belongs to, inferred from its fields (a hint, not a lookup). Gzip-encoded files (as returned by
`gcloud storage cat` for the CI uploads) are read transparently.
"""
import gzip
import json
import sys

# (fields that identify the state, label); first match wins.
STATE_HINTS = [
    ({"runningHash"}, "BlockRecordService.RUNNING_HASHES"),
    ({"lastBlockNumber", "blockHashes"}, "BlockRecordService.BLOCKS"),
    ({"blockNumber", "trailingOutputHashes"}, "BlockStreamService.BLOCK_STREAM_INFO"),
    ({"tpsThrottles"}, "CongestionThrottleService.THROTTLE_USAGE_SNAPSHOTS"),
    ({"genericLevelStarts"}, "CongestionThrottleService.CONGESTION_LEVEL_STARTS"),
    ({"accountId", "tinybarBalance"}, "TokenService.ACCOUNTS"),
    ({"nodeNumber", "stakeToReward"}, "TokenService.STAKING_INFOS"),
    ({"lastNodeFeeDistributionTime"}, "TokenService.NODE_PAYMENTS"),
    ({"numRoundsInStakingPeriod"}, "TokenService.NODE_REWARDS"),
    ({"numAccounts", "numTokens"}, "EntityIdService.ENTITY_COUNTS"),
    ({"tokenId", "accountId", "balance"}, "TokenService.TOKEN_RELS"),
    ({"entries"}, "RecordCache.TRANSACTION_RECEIPTS"),
]


def state_hint(value):
    if not isinstance(value, dict):
        return ""
    keys = set(value)
    for fields, label in STATE_HINTS:
        if fields <= keys:
            return f"  [{label}?]"
    return ""


def open_text(path):
    with open(path, "rb") as f:
        magic = f.read(2)
    if magic == b"\x1f\x8b":
        return gzip.open(path, "rt", encoding="utf-8")
    return open(path, encoding="utf-8")


def load(path):
    out = {}
    with open_text(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rec = json.loads(line)
            key = (rec["p"], rec.get("k"), rec.get("i"))
            value = rec.get("v")
            if isinstance(value, str):
                try:
                    value = json.loads(value)
                except ValueError:
                    pass
            out[key] = value
    return out


def flatten(value, prefix=""):
    if isinstance(value, dict):
        for k, v in value.items():
            yield from flatten(v, f"{prefix}.{k}")
    elif isinstance(value, list):
        for i, v in enumerate(value):
            yield from flatten(v, f"{prefix}[{i}]")
    else:
        yield prefix, value


def as_int(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def is_hash_like(v):
    """Long non-numeric values (base64/hex hashes and hash lists) are summarized by default."""
    s = str(v)
    return len(s) >= 40 and as_int(s) is None


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    show_hashes = "--show-hashes" in sys.argv
    if len(args) != 2:
        print(__doc__)
        sys.exit(2)
    expected, candidate = load(args[0]), load(args[1])
    for key in sorted(set(expected) | set(candidate), key=lambda k: (k[0], str(k[1]), str(k[2]))):
        path, rec_key, index = key
        label = f"p={path}" + (f" key={rec_key}" if rec_key else "") + (f" i={index}" if index is not None else "")
        if key not in candidate:
            print(f"{label}: only in state1 (expected)")
            continue
        if key not in expected:
            print(f"{label}: only in state2 (candidate)")
            continue
        a, b = dict(flatten(expected[key])), dict(flatten(candidate[key]))
        diffs = [(f, a.get(f), b.get(f)) for f in sorted(set(a) | set(b)) if a.get(f) != b.get(f)]
        if not diffs:
            continue
        hashy = [d for d in diffs if is_hash_like(d[1]) or is_hash_like(d[2])]
        print(label + state_hint(expected[key]))
        if hashy and not show_hashes:
            print(f"   {len(hashy)} hash-like field(s) differ: {', '.join(d[0] for d in hashy[:6])}"
                  + (" ..." if len(hashy) > 6 else ""))
        for field, x, y in diffs:
            if not show_hashes and (field, x, y) in hashy:
                continue
            delta = ""
            xi, yi = as_int(x), as_int(y)
            if xi is not None and yi is not None:
                delta = f"   (state2 - state1 = {yi - xi:+,})"
            print(f"   {field}: state1={x}  state2={y}{delta}")


if __name__ == "__main__":
    main()
