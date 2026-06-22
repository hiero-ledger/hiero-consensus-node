# Metrics File Exporter

This module exports application metrics to a file in the [Prometheus text exposition format](https://prometheus.io/docs/instrumenting/exposition_formats/#text-based-format).
Rather than serving metrics for live scraping (see the sibling `openmetrics-httpserver` module), it
periodically appends timestamped snapshots to an on-disk file, building a historical record that can
be ingested later in batch.

The file is meant for ingestion into a Prometheus-compatible time-series backend. In particular,
[**VictoriaMetrics**](https://docs.victoriametrics.com/) — a Prometheus-compatible TSDB — can import
these files directly, e.g. via its
[`/api/v1/import/prometheus`](https://docs.victoriametrics.com/#how-to-import-data-in-prometheus-exposition-format)
endpoint, because every sample carries its own timestamp.

To enable it, add this module to your project's module path. In tests it can be added as a runtime
dependency:

```gradle
runtimeOnly("org.hiero.metrics.export.file")
```

## Output format

The output is a mixture of the Prometheus and OpenMetrics text formats, chosen so the metric names
match what production scraping pipelines store:

- **Counters** get a `_total` suffix and **units** get a `_<unit>` suffix, both folded into the
  metric name (e.g. `bytes_received_byte_total`). `# TYPE` and `# HELP` use that full name.
- `# TYPE` and `# HELP` lines are written **once**, on a metric's first appearance — not in every
  snapshot.
- There is **no `# UNIT`** line (it is not part of the Prometheus text format) and **no `# EOF`**
  terminator (the file is a concatenation of snapshots, not a single exposition).
- Every sample line ends with the snapshot's timestamp in milliseconds since the Unix epoch, shared
  by all samples in the same snapshot.

Because snapshots are simply concatenated over time, the file is **not** a single valid Prometheus
exposition; it is a stream of timestamped samples intended for batch import.

Example (plain text):

```
# TYPE requests_total counter
# HELP requests_total Total requests handled
requests_total{env="prod"} 1 1718636400000
# TYPE heap_byte gauge
heap_byte 1048576 1718636400000
requests_total{env="prod"} 2 1718636403000
heap_byte 1052672 1718636403000
```

## Configuration

Options are defined in
[MetricsFileExportConfig](src/main/java/org/hiero/metrics/export/file/config/MetricsFileExportConfig.java),
under the `metrics.exporter.file` prefix:

| Property                  | Default   | Description                                                                 |
|---------------------------|-----------|-----------------------------------------------------------------------------|
| `enabled`                 | `true`    | Whether the exporter is enabled.                                            |
| `directory`               | _(none)_  | Directory for the output file (required). File is `metrics.txt[.gz]`.       |
| `snapshotIntervalSeconds` | `3`       | Interval between snapshots written to the file (must be positive).          |
| `useGzip`                 | `true`    | Gzip the output. Metrics compress well due to repeated names and labels.    |
| `bufferSize`              | `8192`    | Output buffer size in bytes (`0` disables buffering; max `2097152`).        |
| `decimalFormat`           | `#.###`   | `DecimalFormat` pattern for numeric values (locale-independent `.`).        |

## gzip durability

When `useGzip` is enabled the file is a single continuous gzip stream spanning all snapshots, which
compresses far better than per-snapshot members. The stream uses sync-flush, so each snapshot is
flushed to disk and survives an abrupt process termination; the gzip footer (final CRC) is only
written on a clean shutdown. A file from a process that did not close cleanly still decompresses up
to its last flushed snapshot. Successive process runs append additional gzip members to the same
file, which standard gzip readers decompress transparently.
