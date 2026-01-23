#!/usr/bin/env python3
import argparse
import csv
import json
import re
from pathlib import Path

# Aliases for transaction name prefixes that don't match schedule names directly.
ALIAS_PREFIXES = [
    ("HbarTransferAliasCreate", "CryptoTransfer"),
    ("HbarTransfer", "CryptoTransfer"),
    ("TokenTransferAutoAssoc", "CryptoTransfer"),
    ("TokenTransferFTNFTS", "CryptoTransfer"),
    ("TokenTransferNFTSerials", "CryptoTransfer"),
    ("TokenTransferNFT", "CryptoTransfer"),
    ("TokenTransferFT", "CryptoTransfer"),
    ("TokenTransferCF", "CryptoTransfer"),
    ("TokenTransfer", "CryptoTransfer"),
    ("CryptoApprove", "CryptoApproveAllowance"),
    ("TopicCreate", "ConsensusCreateTopic"),
    ("TopicUpdate", "ConsensusUpdateTopic"),
    ("TopicDelete", "ConsensusDeleteTopic"),
    ("SubmitMsg", "ConsensusSubmitMessage"),
    ("PrngRange", "UtilPrng"),
    ("NetworkGetVersionInfo", "GetVersionInfo"),
    ("TxnGetRecord", "TransactionGetRecord"),
    ("TxnGetReceipt", "TransactionGetReceipt"),
    ("TokenAssociate", "TokenAssociateToAccount"),
    ("TokenDissociate", "TokenDissociateFromAccount"),
    ("TokenFreeze", "TokenFreezeAccount"),
    ("TokenUnfreeze", "TokenUnfreezeAccount"),
    ("TokenGrantKyc", "TokenGrantKycToAccount"),
    ("TokenRevokeKyc", "TokenRevokeKycFromAccount"),
    ("TokenWipeFungible", "TokenAccountWipe"),
    ("TokenWipe", "TokenAccountWipe"),
    ("TokenReject", "TokenReject"),
    ("EthereumCryptoTransfer", "EthereumTransaction"),
    ("FreezeAbort", "Freeze"),
]

# Simple-fee supported transaction and query schedule names.
TX_SIMPLE = {
    "ConsensusCreateTopic",
    "ConsensusDeleteTopic",
    "ConsensusSubmitMessage",
    "ConsensusUpdateTopic",
    "CryptoApproveAllowance",
    "CryptoCreate",
    "CryptoDelete",
    "CryptoDeleteAllowance",
    "CryptoUpdate",
    "CryptoTransfer",
    "ScheduleCreate",
    "ScheduleSign",
    "ScheduleDelete",
    "FileCreate",
    "FileAppend",
    "FileUpdate",
    "FileDelete",
    "SystemDelete",
    "SystemUndelete",
    "UtilPrng",
    "AtomicBatch",
    "TokenCreate",
    "TokenMint",
    "TokenBurn",
    "TokenDelete",
    "TokenFeeScheduleUpdate",
    "TokenFreezeAccount",
    "TokenUnfreezeAccount",
    "TokenAssociateToAccount",
    "TokenDissociateFromAccount",
    "TokenGrantKycToAccount",
    "TokenRevokeKycFromAccount",
    "TokenPause",
    "TokenUnpause",
    "TokenAirdrop",
    "TokenClaimAirdrop",
    "TokenCancelAirdrop",
    "TokenUpdate",
    "TokenUpdateNfts",
    "TokenAccountWipe",
    "TokenReject",
}

QUERY_SIMPLE = {
    "ConsensusGetTopicInfo",
    "ScheduleGetInfo",
    "FileGetContents",
    "FileGetInfo",
    "TokenGetInfo",
    "TokenGetNftInfo",
    "CryptoGetInfo",
    "CryptoGetAccountRecords",
    "CryptoGetAccountBalance",
    "GetVersionInfo",
    "TransactionGetRecord",
    "TransactionGetReceipt",
    "GetByKey",
    "ContractCallLocal",
    "ContractGetBytecode",
    "ContractGetInfo",
}

EXTRA_RE = re.compile(r"([A-Z_]+)\s*=\s*(\d+)")
EXTRA_GT_RE = re.compile(r"([A-Z_]+)\s*>\s*(\d+)")


def load_schedule(path: Path):
    with path.open() as f:
        sched = json.load(f)
    node = sched["node"]
    node_base = node["baseFee"]
    node_extras = {e["name"]: e.get("includedCount", 0) for e in node.get("extras", [])}
    network_multiplier = sched["network"]["multiplier"]
    extra_fees = {e["name"]: e["fee"] for e in sched.get("extras", [])}

    service_defs = {}
    for svc in sched["services"]:
        for entry in svc["schedule"]:
            extras = {e["name"]: e.get("includedCount", 0) for e in entry.get("extras", [])}
            service_defs[entry["name"]] = {
                "baseFee": entry["baseFee"],
                "extras": extras,
            }

    return node_base, node_extras, network_multiplier, extra_fees, service_defs


def parse_extras(extras_str: str):
    counts = {}
    if not extras_str:
        return counts
    # CSV uses ';' instead of ',' for emphasis; we just regex over the whole string.
    for name, val in EXTRA_RE.findall(extras_str):
        counts[name] = int(val)
    for name, val in EXTRA_GT_RE.findall(extras_str):
        # Mark unknown-but-positive counts (e.g., BYTES>0) so we can treat as included.
        if name not in counts:
            counts[name] = f">{val}"
    return counts


def normalize_counts(counts):
    def is_positive(val):
        if isinstance(val, int):
            return val > 0
        if isinstance(val, str) and val.startswith(">"):
            return True
        return False

    if is_positive(counts.get("TOKEN_TRANSFER_BASE_CUSTOM_FEES")):
        counts["TOKEN_TRANSFER_BASE_CUSTOM_FEES"] = 1
        counts["TOKEN_TRANSFER_BASE"] = 0
    elif is_positive(counts.get("TOKEN_TRANSFER_BASE")):
        counts["TOKEN_TRANSFER_BASE"] = 1
    return counts


def map_txn_to_schedule(txn_name: str, schedule_names):
    for name in schedule_names:
        if txn_name.startswith(name):
            return name
    for prefix, mapped in ALIAS_PREFIXES:
        if txn_name.startswith(prefix):
            return mapped
    return None


def calc_service_fee(service_def, extra_fees, counts):
    fee = service_def["baseFee"]
    for name, included in service_def["extras"].items():
        count = counts.get(name)
        if isinstance(count, str) and count.startswith(">"):
            count = included
        if count is None:
            count = included
        if count > included:
            fee += (count - included) * extra_fees.get(name, 0)
    return fee


def calc_node_fee(node_base, node_extras, extra_fees, counts, use_bytes_for_node):
    fee = node_base
    sigs = counts.get("SIGS_ACTUAL", counts.get("SIGS"))
    if isinstance(sigs, str) and sigs.startswith(">"):
        sigs = None
    if sigs is None:
        sigs = node_extras.get("SIGNATURES", 0)
    included_sigs = node_extras.get("SIGNATURES", 0)
    if sigs > included_sigs:
        fee += (sigs - included_sigs) * extra_fees.get("SIGNATURES", 0)

    bytes_count = counts.get("TX_BYTES")
    if bytes_count is None and use_bytes_for_node:
        bytes_count = counts.get("BYTES")
    if isinstance(bytes_count, str) and bytes_count.startswith(">"):
        bytes_count = node_extras.get("BYTES", 0)
    if bytes_count is None:
        bytes_count = node_extras.get("BYTES", 0)
    included_bytes = node_extras.get("BYTES", 0)
    if bytes_count > included_bytes:
        fee += (bytes_count - included_bytes) * extra_fees.get("BYTES", 0)

    return fee


def tinycents_to_tinybars(amount, hbar_equiv, cent_equiv):
    return (amount * hbar_equiv) // cent_equiv


def main():
    parser = argparse.ArgumentParser(description="Validate simple fees in a KitchenSink fee comparison CSV.")
    parser.add_argument("--csv", default="hedera-node/test-clients/fee-comparison-full.csv")
    parser.add_argument("--schedule", default="hedera-node/hedera-file-service-impl/src/main/resources/genesis/simpleFeesSchedules.json")
    parser.add_argument("--hbar-equiv", type=int, default=1)
    parser.add_argument("--cent-equiv", type=int, default=12)
    parser.add_argument("--use-bytes-for-node", action="store_true", help="Use BYTES=... from emphasis to compute node BYTES extras (approximate).")
    parser.add_argument("--only-mismatches", action="store_true")
    parser.add_argument("--show-skipped", action="store_true")
    parser.add_argument("--tolerance", type=int, default=0, help="Allowed tinybar delta.")
    parser.add_argument("--write-csv", action="store_true", help="Write a validated CSV with pass/fail columns.")
    parser.add_argument("--out", help="Output CSV path (defaults to <input>.validated.csv if --write-csv).")

    args = parser.parse_args()

    node_base, node_extras, network_multiplier, extra_fees, service_defs = load_schedule(Path(args.schedule))
    schedule_names = sorted(service_defs.keys(), key=len, reverse=True)

    assoc_fee_tinycents = None
    if "TokenAssociateToAccount" in service_defs:
        assoc_service = calc_service_fee(service_defs["TokenAssociateToAccount"], extra_fees, {"TOKEN_ASSOCIATE": 1})
        assoc_node = node_base
        assoc_fee_tinycents = assoc_service + assoc_node + assoc_node * network_multiplier
    airdrop_fee_tinycents = extra_fees.get("AIRDROPS")

    total = 0
    checked = 0
    ok = 0
    mismatches = 0
    skipped = 0
    unknown = 0

    csv_path = Path(args.csv)
    out_rows = []
    with csv_path.open() as f:
        reader = csv.DictReader(f)
        for row in reader:
            total += 1
            txn = row["Transaction"].strip()
            if txn.upper() == "TOTAL":
                out_rows.append(row)
                continue
            extras_str = row["Extras"].strip()
            try:
                simple_fee = int(row["Simple Fee (tinybars)"])
            except Exception:
                skipped += 1
                if args.show_skipped:
                    print(f"SKIP,INVALID_FEE,{txn}")
                row["Simple Fee Expected (tinybars)"] = ""
                row["Simple Fee Delta (tinybars)"] = ""
                row["Simple Fee OK"] = "SKIP"
                row["Validation Note"] = "INVALID_FEE"
                out_rows.append(row)
                continue

            counts = normalize_counts(parse_extras(extras_str))
            is_inner = "INNER" in counts
            schedule_name = map_txn_to_schedule(txn, schedule_names)
            if is_inner:
                schedule_name = "CryptoTransfer"
            if schedule_name is None:
                unknown += 1
                if args.show_skipped:
                    print(f"UNKNOWN,{txn}")
                row["Simple Fee Expected (tinybars)"] = ""
                row["Simple Fee Delta (tinybars)"] = ""
                row["Simple Fee OK"] = "SKIP"
                row["Validation Note"] = "UNKNOWN"
                out_rows.append(row)
                continue

            is_tx = schedule_name in TX_SIMPLE
            is_query = schedule_name in QUERY_SIMPLE
            if not is_tx and not is_query:
                skipped += 1
                if args.show_skipped:
                    print(f"UNSUPPORTED,{txn},{schedule_name}")
                row["Simple Fee Expected (tinybars)"] = ""
                row["Simple Fee Delta (tinybars)"] = ""
                row["Simple Fee OK"] = "SKIP"
                row["Validation Note"] = "UNSUPPORTED"
                out_rows.append(row)
                continue

            if schedule_name not in service_defs:
                unknown += 1
                if args.show_skipped:
                    print(f"UNKNOWN_SCHEDULE,{txn},{schedule_name}")
                row["Simple Fee Expected (tinybars)"] = ""
                row["Simple Fee Delta (tinybars)"] = ""
                row["Simple Fee OK"] = "SKIP"
                row["Validation Note"] = "UNKNOWN_SCHEDULE"
                out_rows.append(row)
                continue

            if schedule_name == "TokenAirdrop":
                service_fee = calc_service_fee(service_defs["CryptoTransfer"], extra_fees, counts)
                node_fee = calc_node_fee(node_base, node_extras, extra_fees, counts, args.use_bytes_for_node)
                total_tinycents = service_fee + node_fee + node_fee * network_multiplier
                pending = counts.get("PENDING_AIRDROPS", counts.get("AIRDROPS", 0))
                existing = counts.get("EXISTING_PENDING_AIRDROPS", 0)
                unlimited = counts.get("UNLIMITED_ASSOCIATIONS", 0)
                pending = 0 if isinstance(pending, str) else pending
                existing = 0 if isinstance(existing, str) else existing
                unlimited = 0 if isinstance(unlimited, str) else unlimited
                if airdrop_fee_tinycents is not None:
                    new_pending = max(0, pending - existing)
                    if assoc_fee_tinycents is not None:
                        total_tinycents += new_pending * (airdrop_fee_tinycents + assoc_fee_tinycents)
                    else:
                        total_tinycents += new_pending * airdrop_fee_tinycents
                    total_tinycents += existing * airdrop_fee_tinycents
                    total_tinycents += unlimited * airdrop_fee_tinycents
            else:
                service_fee = calc_service_fee(service_defs[schedule_name], extra_fees, counts)
                if is_query:
                    total_tinycents = service_fee
                else:
                    node_fee = calc_node_fee(node_base, node_extras, extra_fees, counts, args.use_bytes_for_node)
                    network_fee = node_fee * network_multiplier
                    total_tinycents = service_fee + node_fee + network_fee
                    created_auto = counts.get("CREATED_AUTO_ASSOCIATIONS")
                    if isinstance(created_auto, str):
                        created_auto = 0
                    if created_auto and assoc_fee_tinycents is not None:
                        total_tinycents += created_auto * assoc_fee_tinycents

            expected = tinycents_to_tinybars(total_tinycents, args.hbar_equiv, args.cent_equiv)
            delta = simple_fee - expected

            checked += 1
            if abs(delta) <= args.tolerance:
                ok += 1
                if not args.only_mismatches:
                    print(f"OK,{txn},{schedule_name},expected={expected},actual={simple_fee},delta={delta}")
                row["Simple Fee OK"] = "PASS"
            else:
                mismatches += 1
                print(f"MISMATCH,{txn},{schedule_name},expected={expected},actual={simple_fee},delta={delta}")
                row["Simple Fee OK"] = "FAIL"

            row["Simple Fee Expected (tinybars)"] = str(expected)
            row["Simple Fee Delta (tinybars)"] = str(delta)
            row.setdefault("Validation Note", "")
            out_rows.append(row)

    print("\nSUMMARY")
    print(f"total_rows={total}")
    print(f"checked_simple={checked}")
    print(f"ok={ok}")
    print(f"mismatches={mismatches}")
    print(f"unsupported={skipped}")
    print(f"unknown={unknown}")

    if args.write_csv or args.out:
        out_path = Path(args.out) if args.out else csv_path.with_suffix(".validated.csv")
        fieldnames = list(reader.fieldnames)
        for col in [
            "Simple Fee Expected (tinybars)",
            "Simple Fee Delta (tinybars)",
            "Simple Fee OK",
            "Validation Note",
        ]:
            if col not in fieldnames:
                fieldnames.append(col)
        with out_path.open("w", newline="") as out_f:
            writer = csv.DictWriter(out_f, fieldnames=fieldnames)
            writer.writeheader()
            for row in out_rows:
                writer.writerow(row)
        print(f"\nWROTE,{out_path}")


if __name__ == "__main__":
    main()
