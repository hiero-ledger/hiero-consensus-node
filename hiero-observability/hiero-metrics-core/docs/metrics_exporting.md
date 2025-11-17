## Metrics Exporting

[📘Back to Overview](metrics_overview.md)

### Export API

Metrics exporting is done via exporters that implement [MetricsExporter](../src/main/java/org/hiero/metrics/api/export/MetricsExporter.java) interface.
There are two types of exporters:
- [PullingMetricsExporter](../src/main/java/org/hiero/metrics/api/export/PullingMetricsExporter.java) - has own export schedule (e.g., Prometheus)
- [PushingMetricsExporter](../src/main/java/org/hiero/metrics/api/export/PushingMetricsExporter.java) - requires export schedule (e.g., export to a file)

[MetricsExporterFactory](../src/main/java/org/hiero/metrics/api/export/MetricsExporterFactory.java) is a service interface that allows creating instances of exporters based on configuration.
To be discovered by SPI mechanism implementations of this interface should be registered either in
`META-INF/services/org.hiero.metrics.api.export.MetricsExporterFactory` or `module-info.java` file of the module.

[MetricsExportManager](../src/main/java/org/hiero/metrics/api/export/MetricsExportManager.java) is the interface to manage metrics registries and exporters.
`MetricsExportManager.Builder` is must be used to create instance of export manager.
It's `withDiscoverExporters()` method can be used to discover all available `MetricsExporterFactory`s in module path and create required exporters.

### Export Internals

One of the important properties of export manager is that taking snapshots of the metrics happens synchronously
and no more than one snapshot could be taken at a time. That allows to reuse snapshot objects for metrics and datapoints.
Each datapoint during creation has associated snapshot object.
When it comes to take a snapshot of the metrics for exporting, all metric datapoint snapshots are updated from their associated datapoint objects.
[AppendArray](../src/main/java/org/hiero/metrics/internal/core/AppendArray.java) allows to fix size of elements (either metric snapshots or datapoint snapshots)
to be ready to read by exporter, and it is called when snapshots are updated synchronously by export manager.
This approach allows to avoid creating new snapshot objects on each export and reduce GC pressure.

Since snapshot objects available for exporters are the same objects, exporters can use them to cache exported representation of the snapshots -
[AbstractCachingMetricsSnapshotsWriter](../src/main/java/org/hiero/metrics/api/export/extension/writer/AbstractCachingMetricsSnapshotsWriter.java)
can be used for that purpose. In that case snapshot object is used as a key and value could be anything related to export destination format.
[ByteArrayTemplate](../src/main/java/org/hiero/metrics/api/export/extension/writer/ByteArrayTemplate.java) is used to represent export data template
where placeholders are replaced with actual values from snapshots.

### Export Extensions

Metrics Core module doesn't provide any built-in exporters, but there are extensions can be added to runtime:
- `hiero-openmetrics-http` - module that provides HTTP server with Prometheus-compatible ([OpenMetrics1.1](https://github.com/prometheus/OpenMetrics/blob/main/specification/OpenMetrics.md)) endpoint to scrape metrics.

Clients may use `org.hiero.metrics.api.export.extension` package to implement their own exporters and factories.
Core classes to use:
- [MetricsSnapshotsWriter](../src/main/java/org/hiero/metrics/api/export/extension/writer/MetricsSnapshotsWriter.java)
- [AbstractCachingMetricsSnapshotsWriter](../src/main/java/org/hiero/metrics/api/export/extension/writer/AbstractCachingMetricsSnapshotsWriter.java)
- [CsvMetricsSnapshotsWriter](../src/main/java/org/hiero/metrics/api/export/extension/writer/CsvMetricsSnapshotsWriter.java)
- [OpenMetricsSnapshotsWriter](../src/main/java/org/hiero/metrics/api/export/extension/writer/OpenMetricsSnapshotsWriter.java)

[📘Back to Overview](metrics_overview.md)
