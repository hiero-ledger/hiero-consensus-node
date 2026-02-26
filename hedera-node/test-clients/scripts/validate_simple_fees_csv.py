#!/usr/bin/env python3
import argparse
import csv
import json
from decimal import Decimal
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
MIN_MULTIPLIER = 1000


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


def load_canonical_prices(path: Path):
    try:
        with path.open() as f:
            raw = json.load(f)
    except FileNotFoundError:
        return None
    canonical = {}
    for func, prices in raw.items():
        canonical[func] = {subtype: Decimal(str(price)) for subtype, price in prices.items()}
    return canonical


def usd_to_tinycents(amount_usd: Decimal) -> int:
    # 1 USD = 100 cents; 1 cent = 1e8 tinycents => 1 USD = 1e10 tinycents.
    return int((amount_usd * Decimal("10000000000")).to_integral_value())


def validate_high_volume_multipliers(path: Path):
    with path.open() as f:
        sched = json.load(f)
    violations = []
    for svc in sched.get("services", []):
        for entry in svc.get("schedule", []):
            high_volume = entry.get("highVolumeRates")
            if not high_volume:
                continue
            name = entry.get("name", "<unknown>")
            max_multiplier = high_volume.get("maxMultiplier")
            if max_multiplier is not None and max_multiplier < MIN_MULTIPLIER:
                violations.append(f"{name}: maxMultiplier {max_multiplier} < {MIN_MULTIPLIER}")
            points = (
                high_volume.get("pricingCurve", {})
                .get("piecewiseLinear", {})
                .get("points", [])
            )
            for point in points:
                multiplier = point.get("multiplier", 0)
                if multiplier < MIN_MULTIPLIER:
                    utilization = point.get("utilizationBasisPoints", "?")
                    violations.append(
                        f"{name}: point multiplier {multiplier} < {MIN_MULTIPLIER} at utilization {utilization}"
                    )
    if violations:
        raise SystemExit("Invalid high-volume multipliers:\n" + "\n".join(violations))


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
    def numeric_count(val):
        if isinstance(val, int):
            return val
        if isinstance(val, str) and val.startswith(">"):
            return 1
        return 0

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

    # If minting NFTs, include the base NFT mint extra once when not present.
    nft_count = counts.get("TOKEN_MINT_NFT")
    if isinstance(nft_count, str) and nft_count.startswith(">"):
        nft_count = 1
    if isinstance(nft_count, int) and nft_count > 0:
        counts.setdefault("TOKEN_MINT_NFT_BASE", 1)

    # Map hook slot updates to HOOK_SLOT_UPDATE extras.
    slot_updates = 0
    for key in ("SLOTS", "REMOVE_SLOTS"):
        val = counts.get(key)
        if isinstance(val, str) and val.startswith(">"):
            val = 1
        if isinstance(val, int):
            slot_updates += val
    if slot_updates:
        counts.setdefault("HOOK_SLOT_UPDATE", slot_updates)

    # Many scenarios label state bytes as BYTES=... in emphasis text.
    if "BYTES" in counts and "STATE_BYTES" not in counts:
        counts["STATE_BYTES"] = counts["BYTES"]

    # TOKEN_TYPES tracks number of token transfer lists, not just token classes.
    fung = numeric_count(counts.get("FUNGIBLE_TOKENS"))
    nft = numeric_count(counts.get("NON_FUNGIBLE_TOKENS"))
    if (fung > 0 or nft > 0) and "TOKEN_TYPES" not in counts:
        counts["TOKEN_TYPES"] = fung + nft

    # Derive hook executions from token transfer lists when hooks are involved.
    hook_execs = counts.get("HOOK_EXECUTION")
    if isinstance(hook_execs, str) and hook_execs.startswith(">"):
        hook_execs = 1
    if isinstance(hook_execs, int) and hook_execs > 0:
        fung = counts.get("FUNGIBLE_TOKENS", 0)
        if isinstance(fung, str) and fung.startswith(">"):
            fung = 1
        if not isinstance(fung, int):
            fung = 0
        nft = counts.get("NON_FUNGIBLE_TOKENS", 0)
        nft_list = 1 if (isinstance(nft, int) and nft > 0) or (isinstance(nft, str) and nft.startswith(">")) else 0
        derived = fung + nft_list
        if derived > hook_execs:
            counts["HOOK_EXECUTION"] = derived
    return counts


def map_txn_to_schedule(txn_name: str, schedule_names):
    for name in schedule_names:
        if txn_name.startswith(name):
            return name
    for prefix, mapped in ALIAS_PREFIXES:
        if txn_name.startswith(prefix):
            return mapped
    return None


def calc_service_fee(service_def, extra_fees, counts, txn_name=""):
    fee = service_def["baseFee"]
    parts = [f"base {service_def['baseFee']}"]
    hook_execs = counts.get("HOOK_EXECUTION", 0)
    if isinstance(hook_execs, str) and hook_execs.startswith(">"):
        hook_execs = 0
    for name, included in service_def["extras"].items():
        count = counts.get(name)
        if isinstance(count, str) and count.startswith(">"):
            count = included
        if count is None:
            # Special case: detect ScheduleCreate for contract call from txn name
            if name == "SCHEDULE_CREATE_CONTRACT_CALL_BASE" and "Call" in txn_name:
                count = 1
            else:
                count = included
        if count > included:
            effective = count - included
            mult = 1
            # Gas is charged per hook execution.
            if name == "GAS" and hook_execs:
                mult = hook_execs
                effective *= hook_execs
            fee += effective * extra_fees.get(name, 0)
            if mult == 1:
                parts.append(f"{name} ({count}-{included})*{extra_fees.get(name, 0)}")
            else:
                parts.append(f"{name} ({count}-{included})*{extra_fees.get(name, 0)}*{mult}")
    return fee, parts


def calc_node_fee(node_base, node_extras, extra_fees, counts, use_bytes_for_node):
    fee = node_base
    parts = [f"base {node_base}"]
    sigs = counts.get("SIGS_ACTUAL", counts.get("SIGS"))
    if isinstance(sigs, str) and sigs.startswith(">"):
        sigs = None
    if sigs is None:
        sigs = node_extras.get("SIGNATURES", 0)
    included_sigs = node_extras.get("SIGNATURES", 0)
    if sigs > included_sigs:
        fee += (sigs - included_sigs) * extra_fees.get("SIGNATURES", 0)
        parts.append(f"SIGNATURES ({sigs}-{included_sigs})*{extra_fees.get('SIGNATURES', 0)}")

    processing_name = "PROCESSING_BYTES" if "PROCESSING_BYTES" in node_extras else "BYTES"
    bytes_count = counts.get("TX_BYTES")
    if bytes_count is None and use_bytes_for_node:
        bytes_count = counts.get(processing_name)
        if bytes_count is None:
            bytes_count = counts.get("BYTES")
    if isinstance(bytes_count, str) and bytes_count.startswith(">"):
        bytes_count = node_extras.get(processing_name, 0)
    if bytes_count is None:
        bytes_count = node_extras.get(processing_name, 0)
    included_bytes = node_extras.get(processing_name, 0)
    if bytes_count > included_bytes:
        per_byte = extra_fees.get(processing_name, extra_fees.get("BYTES", 0))
        fee += (bytes_count - included_bytes) * per_byte
        parts.append(f"{processing_name} ({bytes_count}-{included_bytes})*{per_byte}")

    return fee, parts


def tinycents_to_tinybars(amount, hbar_equiv, cent_equiv):
    return (amount * hbar_equiv) // cent_equiv


def format_fee_with_parts(fee, parts):
    if parts:
        return f"{fee} {{{' + '.join(parts)}}}"
    return str(fee)


def main():
    parser = argparse.ArgumentParser(description="Validate simple fees in a KitchenSink fee comparison CSV.")
    parser.add_argument("--csv", default="hedera-node/test-clients/fee-comparison-full.csv")
    parser.add_argument("--schedule", default="hedera-node/hedera-file-service-impl/src/main/resources/genesis/simpleFeesSchedules.json")
    parser.add_argument("--hbar-equiv", type=int, default=1)
    parser.add_argument("--cent-equiv", type=int, default=12)
    parser.add_argument(
        "--canonical-prices",
        default="hedera-node/hapi-fees/src/main/resources/canonical-prices.json",
        help="Path to canonical-prices.json (used to detect zero-fee queries).",
    )
    parser.add_argument("--use-bytes-for-node", action="store_true", help="Use BYTES=... from emphasis to compute node BYTES extras (approximate).")
    parser.add_argument("--only-mismatches", action="store_true")
    parser.add_argument("--show-skipped", action="store_true")
    parser.add_argument("--tolerance", type=int, default=5, help="Allowed tinybar delta.")
    parser.add_argument(
        "--include-query-payment-fee",
        action="store_true",
        help="For queries, add the CryptoTransfer payment txn fee on top of the query fee (service+node+network).",
    )
    parser.add_argument("--write-csv", action="store_true", help="Write a validated CSV with pass/fail columns.")
    parser.add_argument("--out", help="Output CSV path (defaults to <input>.validated.csv if --write-csv).")

    args = parser.parse_args()

    validate_high_volume_multipliers(Path(args.schedule))

    node_base, node_extras, network_multiplier, extra_fees, service_defs = load_schedule(Path(args.schedule))
    canonical_prices = load_canonical_prices(Path(args.canonical_prices))
    schedule_names = sorted(service_defs.keys(), key=len, reverse=True)

    assoc_fee_tinycents = None
    if "TokenAssociateToAccount" in service_defs:
        assoc_service, _ = calc_service_fee(
            service_defs["TokenAssociateToAccount"], extra_fees, {"TOKEN_ASSOCIATE": 1}
        )
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
                row["Service Fee (tinycents)"] = ""
                row["Node Fee (tinycents)"] = ""
                row["Network Fee (tinycents)"] = ""
                row["Simple Fee OK (delta tinybars)"] = "SKIP"
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
                row["Service Fee (tinycents)"] = ""
                row["Node Fee (tinycents)"] = ""
                row["Network Fee (tinycents)"] = ""
                row["Simple Fee OK (delta tinybars)"] = "SKIP"
                row["Validation Note"] = "UNKNOWN"
                out_rows.append(row)
                continue

            is_tx = schedule_name in TX_SIMPLE
            is_query = schedule_name in QUERY_SIMPLE
            if not is_tx and not is_query:
                skipped += 1
                if args.show_skipped:
                    print(f"UNSUPPORTED,{txn},{schedule_name}")
                row["Service Fee (tinycents)"] = ""
                row["Node Fee (tinycents)"] = ""
                row["Network Fee (tinycents)"] = ""
                row["Simple Fee OK (delta tinybars)"] = "SKIP"
                row["Validation Note"] = "UNSUPPORTED"
                out_rows.append(row)
                continue

            if schedule_name not in service_defs:
                unknown += 1
                if args.show_skipped:
                    print(f"UNKNOWN_SCHEDULE,{txn},{schedule_name}")
                row["Service Fee (tinycents)"] = ""
                row["Node Fee (tinycents)"] = ""
                row["Network Fee (tinycents)"] = ""
                row["Simple Fee OK (delta tinybars)"] = "SKIP"
                row["Validation Note"] = "UNKNOWN_SCHEDULE"
                out_rows.append(row)
                continue

            if schedule_name == "TokenAirdrop":
                service_fee, service_parts = calc_service_fee(
                    service_defs["CryptoTransfer"], extra_fees, counts
                )
                node_fee, node_parts = calc_node_fee(
                    node_base, node_extras, extra_fees, counts, args.use_bytes_for_node
                )
                network_fee = node_fee * network_multiplier
                network_parts = [f"node {node_fee} * mult {network_multiplier}"] if node_fee else []
                pending = counts.get("PENDING_AIRDROPS", counts.get("AIRDROPS", 0))
                existing = counts.get("EXISTING_PENDING_AIRDROPS", 0)
                unlimited = counts.get("UNLIMITED_ASSOCIATIONS", 0)
                pending = 0 if isinstance(pending, str) else pending
                existing = 0 if isinstance(existing, str) else existing
                unlimited = 0 if isinstance(unlimited, str) else unlimited
                if airdrop_fee_tinycents is not None:
                    new_pending = max(0, pending - existing)
                    if assoc_fee_tinycents is not None:
                        if new_pending:
                            service_fee += new_pending * (airdrop_fee_tinycents + assoc_fee_tinycents)
                            service_parts.append(
                                f"NEW_PENDING_AIRDROPS ({new_pending})*({airdrop_fee_tinycents}+{assoc_fee_tinycents})"
                            )
                    else:
                        if new_pending:
                            service_fee += new_pending * airdrop_fee_tinycents
                            service_parts.append(
                                f"NEW_PENDING_AIRDROPS ({new_pending})*{airdrop_fee_tinycents}"
                            )
                    if existing:
                        service_fee += existing * airdrop_fee_tinycents
                        service_parts.append(
                            f"EXISTING_PENDING_AIRDROPS ({existing})*{airdrop_fee_tinycents}"
                        )
                    if unlimited:
                        service_fee += unlimited * airdrop_fee_tinycents
                        service_parts.append(
                            f"UNLIMITED_ASSOCIATIONS ({unlimited})*{airdrop_fee_tinycents}"
                        )
                total_tinycents = service_fee + node_fee + network_fee
            else:
                service_fee, service_parts = calc_service_fee(
                    service_defs[schedule_name], extra_fees, counts, txn
                )
                node_fee = 0
                node_parts = []
                network_fee = 0
                network_parts = []
                if is_query:
                    canonical_free_query = False
                    if canonical_prices is not None:
                        canonical_default = canonical_prices.get(schedule_name, {}).get("DEFAULT")
                        if canonical_default is not None:
                            canonical_free_query = usd_to_tinycents(canonical_default) == 0
                    if canonical_free_query:
                        node_fee = 0
                        node_parts = []
                        network_fee = 0
                        network_parts = []
                        total_tinycents = 0
                    else:
                        node_fee, node_parts = calc_node_fee(
                            node_base, node_extras, extra_fees, counts, args.use_bytes_for_node
                        )
                        network_fee = node_fee * network_multiplier
                        network_parts = [f"node {node_fee} * mult {network_multiplier}"] if node_fee else []
                        total_tinycents = service_fee + node_fee + network_fee
                else:
                    node_fee, node_parts = calc_node_fee(
                        node_base, node_extras, extra_fees, counts, args.use_bytes_for_node
                    )
                    network_fee = node_fee * network_multiplier
                    network_parts = [f"node {node_fee} * mult {network_multiplier}"] if node_fee else []
                    created_auto = counts.get("CREATED_AUTO_ASSOCIATIONS")
                    if isinstance(created_auto, str):
                        created_auto = 0
                    if created_auto and assoc_fee_tinycents is not None:
                        service_fee += created_auto * assoc_fee_tinycents
                        service_parts.append(
                            f"CREATED_AUTO_ASSOCIATIONS ({created_auto})*{assoc_fee_tinycents}"
                        )
                    total_tinycents = service_fee + node_fee + network_fee

            expected_query_fee = tinycents_to_tinybars(total_tinycents, args.hbar_equiv, args.cent_equiv)
            expected = expected_query_fee
            if is_query and args.include_query_payment_fee:
                if simple_fee == 0:
                    expected = 0
                else:
                    payment_service_fee, _ = calc_service_fee(
                        service_defs["CryptoTransfer"], extra_fees, counts, "QueryPayment"
                    )
                    payment_node_fee, _ = calc_node_fee(
                        node_base, node_extras, extra_fees, counts, args.use_bytes_for_node
                    )
                    payment_network_fee = payment_node_fee * network_multiplier
                    payment_total = payment_service_fee + payment_node_fee + payment_network_fee
                    payment_fee = tinycents_to_tinybars(payment_total, args.hbar_equiv, args.cent_equiv)
                    expected = expected_query_fee + payment_fee
            delta = simple_fee - expected

            checked += 1
            if abs(delta) <= args.tolerance:
                ok += 1
                if not args.only_mismatches:
                    print(f"OK,{txn},{schedule_name},expected={expected},actual={simple_fee},delta={delta}")
                status = "Pass"
            else:
                mismatches += 1
                print(f"MISMATCH,{txn},{schedule_name},expected={expected},actual={simple_fee},delta={delta}")
                status = "Fail"

            row["Service Fee (tinycents)"] = format_fee_with_parts(service_fee, service_parts)
            row["Node Fee (tinycents)"] = format_fee_with_parts(node_fee, node_parts)
            row["Network Fee (tinycents)"] = format_fee_with_parts(network_fee, network_parts)
            row["Simple Fee OK (delta tinybars)"] = f"{status} [delta={delta}]"
            if is_query and args.include_query_payment_fee and simple_fee != 0:
                row["Validation Note"] = "INCLUDES_QUERY_PAYMENT_FEE"
            else:
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
        input_fieldnames = list(reader.fieldnames)
        drop_cols = {
            "Service Fee (tinycents)",
            "Node Fee (tinycents)",
            "Network Fee (tinycents)",
            "Simple Fee OK (delta tinybars)",
            "Validation Note",
            "Simple Fee Expected (tinybars)",
            "Simple Fee Delta (tinybars)",
            "Simple Fee OK",
            "Simple Fee Expected (tinycents)",
            "Calc Details",
        }
        fieldnames = [c for c in input_fieldnames if c not in drop_cols]
        new_cols = [
            "Service Fee (tinycents)",
            "Node Fee (tinycents)",
            "Network Fee (tinycents)",
            "Simple Fee OK (delta tinybars)",
            "Validation Note",
        ]
        insert_after = "% Change"
        if insert_after in fieldnames:
            idx = fieldnames.index(insert_after) + 1
            for col in new_cols:
                fieldnames.insert(idx, col)
                idx += 1
        else:
            fieldnames.extend(new_cols)
        with out_path.open("w", newline="") as out_f:
            writer = csv.DictWriter(out_f, fieldnames=fieldnames, extrasaction="ignore")
            writer.writeheader()
            for row in out_rows:
                writer.writerow(row)
        print(f"\nWROTE,{out_path}")


if __name__ == "__main__":
    main()
