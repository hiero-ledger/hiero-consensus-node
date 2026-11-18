# Consensus node metric CSV format

Each node writes one CSV (usually `MainNetStats<N>.csv`, sometimes renamed, e.g. `MainNetStats<N>_<label>.csv`).
Files are usually in a separate folder per node and may arrive as `.gz`, `.zip` or `.tar.gz`.

## Layout

```
filename:,data/stats/MainNetStats0.csv,
endsReconnectAsReceiver:,number of times a node ends reconnect as a receiver,
...                                   <- one "<metric>:,<description>," line per metric
                                      <- blank line
,,Reconnect,Reconnect,...,platform,...  <- category row
,,endsReconnectAsReceiver,...,trans_per_sec,...  <- name row
,,0,0,...                             <- data rows, one per sample
```

- The first two columns are always empty.
- `time` (category `platform:info`) is the sample's wall-clock time, `YYYY-MM-DD HH:MM:SS UTC`.
- `memberID` (category `platform:info`) is the node id. Discovery uses it before the file name.
- Values of ±9.2e18 (Java `Long.MIN_VALUE`/`MAX_VALUE`) mean "no value yet". They are turned into gaps, as are
  `NaN` and `Infinity` cells.
  `2147483647` (`Integer.MAX_VALUE`) also appears in some columns. It is left as is, because in some metrics it could be a real value.
- Rows with no numeric values (a repeated header after a restart) are skipped, and `prepare.py` reports how many.
  If a restart changes the set of columns, the later rows are read using the first header. This case hasn't been seen in practice yet.

## Categories, topics and families

Reports organize metrics as **category → topic → family → metric**.

**Categories.** The CSV's category row has `platform`, `app`, `merkle_db`, `blockStream`, `crypto`, `virtual_map`,
`block` and `Reconnect`, plus some that aren't useful categories:
- `internal` is a catch-all: it mixes consensus, latency, transaction, disk and ISS metrics.
- `bpss` (bytes sent per peer), `ping`, `platform:info` and `app_` are tiny.

[topics.md](topics.md) moves all of these into the real categories, so reports and the menu never show them. Each
metric still keeps its CSV category, which is needed to read its column (`csv_category` in `catalog.tsv`).

**Topics** are curated, meaning-based groups inside a category, defined in [topics.md](topics.md). Examples: platform /
Consensus latency (`secC2C`, `secR2C`, …) and platform / Gossip & sync. They are needed because related metrics often
share no name pattern. A metric no topic claims goes to "Other", and `catalog.py --menu` lists it.

**Families** are computed automatically, within a category and topic. Inside a category, names use their own prefixes
(`ds_`, `sync`, `eventLatency`, `conn`, `rpc`, `SmartContractService:`) or none at all. Related metrics often share a
*suffix*, not a prefix:

| Family key                                      | Varies by           |
|-------------------------------------------------|---------------------|
| `*_unhandled_task_count`, `*_busy_fraction`     | component / queue   |
| `bytes_per_sec_sent_*`, `disconnects_per_sec_*` | peer index          |
| `ds_compactions_level_*_hashesTimeMs_state`     | merkledb level      |
| `SmartContractService:TransactionDuration_*`    | statistic (avg/sum) |
| `pityParent*`, `tipsetParent*`                  | trailing node index |
| `numVotingRounds*`                              | value and its MAX   |

How family keys are formed:
- **One token replaced.** A family key is the metric name with one `_`-separated token replaced by `*`, or with a
  trailing number or `MAX`/`MIN` replaced by `*`. The key must match at least two metrics.
- **No overly broad keys.** A leading `*` is allowed only when at least two fixed tokens remain, so a key as broad as
  `*_avg` is never used.
- **No unit-only suffixes.** A leading `*` is also not allowed when the shared suffix only names a unit, meaning it
  starts with a word in `GENERIC_SUFFIX_STARTS` in `catalog.py` (currently `per`). So `*_per_sec` is not a family:
  `trans_per_sec` (transactions received) and `syncs_per_sec` (gossip syncs) measure unrelated things. Rates that count
  the same thing still group by their shared start, e.g. `trans_per_*` = `trans_per_sec` + `trans_per_event`.
- **Largest family wins** when a metric could belong to more than one.
- **Never across categories or topics,** so the sidebar is a strict category → topic → family tree.
