#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Counts the shape of every event in mainnet block streams and prints the tables of EventShape.MAINNET.

What it is for: the benchmarks that process events take the shape of their events from EventShape: the number of other
parents, the number of transactions per event, and the size of each transaction. EventShape.MAINNET holds a frequency
table of each quantity, counted by this script from a week of mainnet block streams. See
docs/proposals/consensus-benchmarks/benchmark-inputs.md, section "Production Data".

Input: the block files of one node, each a gzipped Block. Every node writes every event, so one node's files hold them
all. Mainnet's files are in a requester-pays bucket, one folder per node. The names are block numbers; a listing shows
when each block was uploaded, which finds the blocks of a period:

    gcloud storage ls -l --billing-project=<project> \\
        'gs://hedera-mainnet-streams/block-preview/mainnet/0/0/000000000000000000000000000108*'
    gcloud storage cp -I --billing-project=<project> <dir>/ < <file with the URLs of the period's blocks>

A few blocks more at either end are harmless: --start and --end keep whole hours of consensus time.

Usage: event-shapes.py [--jobs N] [--save FILE] [--min-frequency F] [--start HOUR] [--end HOUR] <file or dir>...
       event-shapes.py --load FILE [--min-frequency F] [--start HOUR] [--end HOUR]

EventShape.MAINNET comes from: event-shapes.py --start 2026-10-18 --end 2026-10-25 <the blocks of node 0>

What it counts. HandleWorkflow#handleEvents writes an EventHeader for each event of a round, followed by the
signed_transaction items of the event's transactions:
- other parents: the parents whose creator is not the event's creator. The self parent is the parent with the event's
  creator. A parent in the same block is written as the index of its EventHeader in the block
  (HandleWorkflow#writeEventHeader), so the script looks its creator up there.
- transactions: the signed_transaction items after the EventHeader whose TransactionID has nonce 0 and is not
  scheduled. These are the event's own transactions, including those the nodes create themselves, such as state
  signatures. The synthetic transactions of setup
  and scheduled work are in the stream too, mixed in with them: HandleWorkflow#handleRound writes the first
  EventHeader of a round before the round's setup work. Each synthetic transaction has a nonce or the scheduled flag:
  system dispatches take the next dispatch nonce (SystemTransactions#nextSystemTransactionIdAt), children take nonces
  from 1 (SavepointStackImpl), and scheduled transactions are flagged (HandlerUtility#scheduledTxnIdFrom). An event's
  own transaction has neither: pre-handle rejects it otherwise (TransactionChecker#checkTransactionID).
- transaction size: the length of the signed_transaction bytes. For the event's own transactions, these are the bytes
  the event carries (TransactionChecker#parseSignedAndCheck keeps them), as TransactionWrapper#getSize counts them.

Output: one table per quantity for the whole period, and the Java to paste. The values fall into bins about 25% wide,
in which small values keep a bin of their own. Each bin with at least --min-frequency of the counts is a row, at the
mean of its values, which keeps the total of the values, such as the transactions' bytes, nearly unchanged. Below the
tables, each hour of the day, each day, and the weekdays and the weekend are compared with the whole period.

Checks: the decoding is strict. A field that a message read here does not define, or a length that does not end where
its message ends, stops the script. The block numbers must have no gaps; the script reports every gap, and every
redacted or filtered item, which would hide a transaction.

Parsing a week of files takes a while; --save writes the counts to a JSON file, and --load reports from it instead.
"""
import argparse
import datetime
import gzip
import json
import math
import os
import sys
from collections import Counter
from concurrent.futures import ProcessPoolExecutor

VARINT, I64, LEN, I32 = 0, 1, 2, 5
# The fields each message defines, with their wire types
BLOCK = {1: LEN}
BLOCK_ITEM = {number: LEN for number in (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 19)}
ITEM_NAMES = {1: "block_header", 2: "event_header", 3: "round_header", 4: "signed_transaction",
              5: "transaction_result", 6: "transaction_output", 7: "state_changes", 8: "filtered_single_item",
              9: "block_proof", 10: "record_file", 11: "trace_data", 12: "block_footer", 19: "redacted_item"}
HIDING_ITEMS = (8, 19)
BLOCK_HEADER = {1: LEN, 2: LEN, 3: VARINT, 4: LEN, 5: VARINT}
TIMESTAMP = {1: VARINT, 2: VARINT}
EVENT_HEADER = {1: LEN, 2: LEN}
EVENT_CORE = {1: VARINT, 2: VARINT, 3: LEN, 5: VARINT}
PARENT_EVENT_REFERENCE = {1: LEN, 2: VARINT}
EVENT_DESCRIPTOR = {1: LEN, 2: VARINT, 3: VARINT}
SIGNED_TRANSACTION = {1: LEN, 2: LEN, 3: VARINT}
TRANSACTION_ID = {1: LEN, 2: LEN, 3: VARINT, 4: VARINT}

QUANTITIES = ("other_parents", "transactions_per_event", "transaction_size")
# Each bin of a table is this much wider than the one before (bin_of)
BIN_RATIO = 1.25


def read_varint(buf, pos, end):
    result = shift = 0
    while True:
        if pos >= end or shift > 63:
            raise ValueError("malformed varint")
        byte = buf[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, pos
        shift += 7


def fields(buf, pos, end, schema, message):
    """Yields (field number, value) of a protobuf message; a LEN value is its (start, end) in buf. A schema of None
    accepts any field."""
    while pos < end:
        key, pos = read_varint(buf, pos, end)
        number, wire_type = key >> 3, key & 7
        if schema is not None and schema.get(number) != wire_type:
            raise ValueError(f"{message} has no field {number} of wire type {wire_type}")
        if wire_type == VARINT:
            value, pos = read_varint(buf, pos, end)
        elif wire_type == LEN:
            length, pos = read_varint(buf, pos, end)
            value = (pos, pos + length)
            pos += length
        elif wire_type == I64:
            value, pos = None, pos + 8
        elif wire_type == I32:
            value, pos = None, pos + 4
        else:
            raise ValueError(f"{message} has field {number} of unknown wire type {wire_type}")
        yield number, value
    if pos != end:
        raise ValueError(f"{message} ends {pos - end} bytes after its length")


def field(buf, span, schema, message, number, default=0):
    """Returns a field of a message, or the default if absent (proto3 omits default values)."""
    for found, value in fields(buf, span[0], span[1], schema, message):
        if found == number:
            return value
    return default


def is_own_transaction(buf, span):
    """Whether a signed_transaction is one of the event's own: its TransactionID has nonce 0 and is not scheduled."""
    body = field(buf, span, SIGNED_TRANSACTION, "SignedTransaction", 1, None)
    if body is None:
        raise ValueError("SignedTransaction has no body")
    # A TransactionBody has many fields; only its TransactionID (field 1) matters here
    transaction_id = field(buf, body, None, "TransactionBody", 1, None)
    if transaction_id is None:
        return True
    scheduled = nonce = 0
    for number, value in fields(buf, transaction_id[0], transaction_id[1], TRANSACTION_ID, "TransactionID"):
        if number == 3:
            scheduled = value
        elif number == 4:
            nonce = value
    return nonce == 0 and not scheduled


def new_hour():
    return {"events": 0, **{q: Counter() for q in QUANTITIES}}


def parse_file(path):
    """Counts the shapes of the events in one block file, per hour of the block's consensus time."""
    with gzip.open(path, "rb") as f:
        buf = f.read()
    try:
        return parse_block(buf, os.path.basename(path))
    except (ValueError, IndexError) as e:
        raise ValueError(f"{path}: {e}") from e


def parse_block(buf, name):
    result = {"file": name, "block": None, "events": 0, "transactions": 0, "bytes": 0, "synthetic": 0,
              "items": Counter(), "hours": {}}
    for event in block_events(buf, result):
        sizes = [end - start for start, end in event["transactions"]]
        counts = result["hours"].setdefault(event["hour"], new_hour())
        counts["events"] += 1
        counts["other_parents"][sum(1 for c in event["parents"] if c != event["creator"])] += 1
        counts["transactions_per_event"][len(sizes)] += 1
        counts["transaction_size"].update(sizes)
        result["events"] += 1
        result["transactions"] += len(sizes)
        result["bytes"] += sum(sizes)
    if result["block"] is None:
        raise ValueError("no BlockHeader")
    return result


def block_events(buf, result):
    """Yields the events of a block, in consensus order: the hour of the block, the creator, the span of the
    EventCore, the creators of the parents, and the spans of the event's own transactions. Counts the block's items
    and synthetic transactions in result."""
    creators = []  # the creator of each event in the block, by the index of its EventHeader
    hour = None
    event = None  # the event whose transactions follow

    for _, item in fields(buf, 0, len(buf), BLOCK, "Block"):
        kinds = list(fields(buf, item[0], item[1], BLOCK_ITEM, "BlockItem"))
        if len(kinds) != 1:
            raise ValueError(f"BlockItem at {item[0]} has {len(kinds)} fields")
        kind, span = kinds[0]
        result["items"][ITEM_NAMES[kind]] += 1
        if kind == 1:
            result["block"] = field(buf, span, BLOCK_HEADER, "BlockHeader", 3)
            timestamp = field(buf, span, BLOCK_HEADER, "BlockHeader", 4, None)
            if timestamp is None:
                raise ValueError("BlockHeader has no block_timestamp")
            hour = field(buf, timestamp, TIMESTAMP, "Timestamp", 1) // 3600
        elif kind == 2:
            if hour is None:
                raise ValueError(f"EventHeader at {span[0]} before the BlockHeader")
            if event is not None:
                yield event
            core = None
            parents = []
            for number, value in fields(buf, span[0], span[1], EVENT_HEADER, "EventHeader"):
                if number == 1:
                    core = value
                else:
                    reference = list(fields(buf, value[0], value[1], PARENT_EVENT_REFERENCE, "ParentEventReference"))
                    if len(reference) > 1:
                        raise ValueError(f"ParentEventReference at {value[0]} has {len(reference)} fields")
                    if reference and reference[0][0] == 1:
                        parents.append(field(buf, reference[0][1], EVENT_DESCRIPTOR, "EventDescriptor", 2))
                    else:
                        # an index into the block's events; an empty reference is index 0, omitted as a default
                        index = reference[0][1] if reference else 0
                        if index >= len(creators):
                            raise ValueError(f"parent index {index} of event {len(creators)} is not an earlier event")
                        parents.append(creators[index])
            if core is None:
                raise ValueError(f"EventHeader at {span[0]} has no EventCore")
            creator = field(buf, core, EVENT_CORE, "EventCore", 1)
            creators.append(creator)
            event = {"hour": hour, "creator": creator, "core": core, "parents": parents, "transactions": []}
        elif kind == 4:
            if is_own_transaction(buf, span):
                if event is None:
                    raise ValueError(f"transaction at {span[0]} before the first EventHeader")
                event["transactions"].append(span)
            else:
                result["synthetic"] += 1
    if event is not None:
        yield event


def input_files(paths):
    files = []
    for path in paths:
        if os.path.isdir(path):
            files += [os.path.join(path, f) for f in os.listdir(path) if f.endswith(".blk.gz")]
        else:
            files.append(path)
    # the names are zero-padded block numbers
    return sorted(files, key=os.path.basename)


def count(files, jobs):
    """Parses the files in parallel and merges their counts, in block order."""
    totals = {"blocks": 0, "first_block": None, "last_block": None, "gaps": [], "events": 0, "transactions": 0,
              "bytes": 0, "synthetic": 0, "items": Counter(), "hours": {}}
    with ProcessPoolExecutor(max_workers=jobs) as executor:
        for i, result in enumerate(executor.map(parse_file, files, chunksize=16)):
            block = result["block"]
            if totals["last_block"] is not None and block != totals["last_block"] + 1:
                totals["gaps"].append((totals["last_block"], block))
            if totals["first_block"] is None:
                totals["first_block"] = block
            totals["last_block"] = block
            totals["blocks"] += 1
            for key in ("events", "transactions", "bytes", "synthetic"):
                totals[key] += result[key]
            totals["items"].update(result["items"])
            for hour, counts in result["hours"].items():
                merged_hour = totals["hours"].setdefault(hour, new_hour())
                merged_hour["events"] += counts["events"]
                for q in QUANTITIES:
                    merged_hour[q].update(counts[q])
            if (i + 1) % 5000 == 0:
                print(f"{i + 1} of {len(files)} blocks", file=sys.stderr)
    return totals


def save(totals, path):
    with open(path, "w") as f:
        json.dump(totals, f)


def load(path):
    with open(path) as f:
        totals = json.load(f)
    # JSON object keys are strings
    totals["items"] = Counter(totals["items"])
    totals["hours"] = {
        int(hour): {"events": counts["events"],
                    **{q: Counter({int(k): v for k, v in counts[q].items()}) for q in QUANTITIES}}
        for hour, counts in totals["hours"].items()
    }
    return totals


def merged(totals, hours):
    """The counts of each quantity over the given hours."""
    counts = {q: Counter() for q in QUANTITIES}
    for hour in hours:
        for q in QUANTITIES:
            counts[q].update(totals["hours"][hour][q])
    return counts


def quantile(counter, q):
    """The smallest value v with P(X <= v) >= q, as EmpiricalDistribution#quantile defines it."""
    total = sum(counter.values())
    running = 0
    for value in sorted(counter):
        running += counter[value]
        if running >= q * total:
            return value
    return max(counter)


def mean(counter):
    return sum(v * c for v, c in counter.items()) / sum(counter.values())


def bin_of(value):
    """The lower edge of the value's bin. The bins start at 0 and 1; each next one starts at the previous start times
    BIN_RATIO, rounded up, and at least one higher: 0, 1, 2, 3, 4, 5, 7, 9, 12, 15, 19, 24, ... Small values keep a bin
    of their own."""
    if value < 1:
        return value
    edge = 1
    while True:
        upper = max(edge + 1, math.ceil(edge * BIN_RATIO))
        if value < upper:
            return edge
        edge = upper


def binned(counter):
    """Merges the counts of each bin: {lower edge: Counter of the values in the bin}."""
    bins = {}
    for value, c in counter.items():
        bins.setdefault(bin_of(value), Counter())[value] = c
    return dict(sorted(bins.items()))


def table(counter, min_frequency):
    """The frequency table of a quantity: one row per bin with at least min_frequency of the counts, as (the mean of
    the values in the bin, rounded; the count of the bin). The mean keeps the total of the values, such as the bytes of
    the transactions, nearly unchanged."""
    total = sum(counter.values())
    return [(round(mean(values)), sum(values.values()))
            for values in binned(counter).values() if sum(values.values()) >= min_frequency * total]


def distance(counter, reference):
    """The total variation distance of two distributions over the bins: the share of draws that would land in a
    different bin."""
    a, b = binned(counter), binned(reference)
    total_a, total_b = sum(counter.values()), sum(reference.values())
    return sum(abs(sum(a.get(k, {}).values()) / total_a - sum(b.get(k, {}).values()) / total_b)
               for k in set(a) | set(b)) / 2


def utc(hour):
    return datetime.datetime.fromtimestamp(hour * 3600, datetime.timezone.utc)


def java(rows):
    """A table as EventShape#table takes it: one row per value, with its count."""
    return "table(new long[][] {" + ", ".join(f"{{{v:_}, {c:_}}}" for v, c in rows) + "})"


def report(totals, min_frequency):
    hours = sorted(totals["hours"])
    hiding = sum(totals["items"][ITEM_NAMES[kind]] for kind in HIDING_ITEMS)
    print("Input, all blocks read")
    print(f"  blocks                  {totals['blocks']:,} ({totals['first_block']} to {totals['last_block']}), "
          f"{len(totals['gaps'])} gaps {totals['gaps'][:10]}")
    print(f"  events                  {totals['events']:,}")
    print(f"  transactions            {totals['transactions']:,}")
    print(f"  transaction bytes       {totals['bytes']:,}")
    print(f"  synthetic transactions  {totals['synthetic']:,} (left out)")
    print(f"  redacted or filtered    {hiding:,}" + (" -- these hide transactions, the counts are wrong" if hiding else ""))
    print()
    print(f"Period: {utc(hours[0]):%Y-%m-%d %H:00} to {utc(hours[-1] + 1):%Y-%m-%d %H:00} UTC, {len(hours)} hours, "
          f"{sum(totals['hours'][h]['events'] for h in hours):,} events, by the consensus time of their block")
    print()

    whole = merged(totals, hours)
    tables = {}
    for q in QUANTITIES:
        counter = whole[q]
        total = sum(counter.values())
        rows = tables[q] = table(counter, min_frequency)
        kept = Counter(dict(rows))
        print(f"{q}: {len(counter)} values in {len(binned(counter))} bins, {len(rows)} rows with a "
              f"frequency of at least {min_frequency:g}; dropped share {1 - sum(kept.values()) / total:.1e}")
        print(f"  mean {mean(counter):.3f} counted, {mean(kept):.3f} in the table")
        for q_level in (0.5, 0.9, 0.99, 0.999, 0.9999):
            print(f"  quantile {q_level:<6} {quantile(counter, q_level):>6} counted, {quantile(kept, q_level):>6} "
                  f"in the table")
        running = 0
        print(f"  {'value':>8} {'count':>14} {'share':>10} {'cumulative':>10}")
        for value, c in rows:
            running += c
            print(f"  {value:>8} {c:>14,} {c / sum(kept.values()):10.6f} {running / sum(kept.values()):10.6f}")
        print()

    print("Java, for EventShape.MAINNET:")
    print(f"  {java(tables['other_parents'])},")
    print(f"  {java(tables['transactions_per_event'])},")
    print(f"  {java(tables['transaction_size'])}")
    print()

    groups = {}
    for hour in hours:
        groups.setdefault(f"{utc(hour):%H}:00", []).append(hour)
    for hour in hours:
        groups.setdefault(f"{utc(hour):%a %Y-%m-%d}", []).append(hour)
    for hour in hours:
        groups.setdefault("weekend" if utc(hour).weekday() >= 5 else "weekdays", []).append(hour)
    print("By hour of the day, day, and weekdays against the weekend (UTC). distance: the share of draws that would")
    print("land in a different row than with the table of the whole period.")
    print(f"  {'':16} {'events/s':>8} | {'other parents':^16} | {'transactions per event':^38} | "
          f"{'transaction size (bytes)':^38}")
    print(f"  {'':16} {'':>8} | {'mean':>6} {'distance':>9} | {'mean':>6} {'p99':>5} {'p99.9':>6} {'p99.99':>6} "
          f"{'distance':>9} | {'mean':>6} {'p50':>5} {'p90':>5} {'p99':>6} {'distance':>9}")
    for name, group in groups.items():
        counts = merged(totals, group)
        events = sum(totals["hours"][h]["events"] for h in group)
        op, tpe, size = (counts[q] for q in QUANTITIES)
        d = {q: distance(counts[q], whole[q]) for q in QUANTITIES}
        print(f"  {name:16} {events / (3600 * len(group)):8.0f} | {mean(op):6.3f} {d['other_parents']:9.4f} | "
              f"{mean(tpe):6.3f} {quantile(tpe, 0.99):5} {quantile(tpe, 0.999):6} {quantile(tpe, 0.9999):6} "
              f"{d['transactions_per_event']:9.4f} | {mean(size):6.0f} {quantile(size, 0.5):5} "
              f"{quantile(size, 0.9):5} {quantile(size, 0.99):6} {d['transaction_size']:9.4f}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--jobs", type=int, default=os.cpu_count(), help="parallel parsers (default: all cores)")
    parser.add_argument("--save", help="write the counts to this JSON file")
    parser.add_argument("--load", help="report from counts written by --save instead of parsing files")
    parser.add_argument("--min-frequency", type=float, default=1e-4,
                        help="keep the rows with at least this frequency (default 1e-4)")
    parser.add_argument("--start", help="first hour to report, UTC, as 2026-10-18 or 2026-10-18T05 (default: all)")
    parser.add_argument("--end", help="hour after the last to report, UTC, in the same format (default: all)")
    parser.add_argument("paths", nargs="*", help="block files (.blk.gz), or directories that hold them")
    args = parser.parse_args()
    if args.load:
        totals = load(args.load)
    else:
        files = input_files(args.paths)
        if not files:
            parser.error("no .blk.gz files given")
        totals = count(files, args.jobs)
        if args.save:
            save(totals, args.save)
    # The blocks at the edges cover only part of an hour; the period keeps whole hours
    start = epoch_hour(args.start) if args.start else min(totals["hours"])
    end = epoch_hour(args.end) if args.end else max(totals["hours"]) + 1
    totals["hours"] = {h: counts for h, counts in totals["hours"].items() if start <= h < end}
    report(totals, args.min_frequency)


def epoch_hour(text):
    moment = datetime.datetime.strptime(text, "%Y-%m-%dT%H" if "T" in text else "%Y-%m-%d")
    return int(moment.replace(tzinfo=datetime.timezone.utc).timestamp()) // 3600


if __name__ == "__main__":
    main()
