## Metrics Overview

Metrics are one of the three pillars of observability, alongside logging and tracing.<br/>
Metrics provide quantitative, numerical data about a system’s state and performance, typically sampled at regular intervals.
They help track trends, resource usage, and system health over time.

### Goals

1. Minimum dependencies.
2. High performance with low memory footprint.
3. Support for different exporters (e.g., Prometheus, JMX, CSV, etc.).
4. Support client aggregations and resetting on export.
5. Support plugging any external code into metrics.
6. Provide SPI for easy configuration.

### Architecture

![metrics_architecture.svg](img/metrics_architecture.svg)

### Key Concepts

In many standards like **OpenMetrics** and **OpenTelemetry** metric has these key attributes:
- Name, description, unit, type
- Labels (key-value string pairs)
- Value(s) (numeric value)

Metric labels provide dimensionality to metrics, allowing for more granular analysis.
Each unique combination of labels (even empty) represents a distinct time series within the metric over time with exporting.
Metric value(s), uniquely identified by the combination of metric name and labels.
**Measurement** serve as a container for the actual metric value(s) and provide methods
to update them on observation and retrieve value(s) during exporting.<br/>
**Metric Registry** acts as a central repository for managing and organizing metrics and providing snapshots to metrics exporter.

---

Metric API doesn't provide explicit methods to export metrics.
Instead, it provides interface to implement exporter and an exporter factory to create and configure it.
Some metrics support client aggregations (tracking min, max, etc.) and allow to reset aggregated value on export.

---

The Metrics Core module is split into two root packages:
- `org.hiero.metrics.api` - contains public API for recording and exporting metrics.
- `org.hiero.metrics.internal` - contains internal implementation of the API, hiding snapshotting implementation.

The Metrics Core module has an optional dependency on `com.swirlds.config.api` for configuration support while discovering exporter factory SPI implementation.

Entry points for clients are:
- All metrics defined in `org.hiero.metrics.api` package, that can be used to create and update metrics.
- [MetricsRegistry](../src/main/java/org/hiero/metrics/api/core/MetricRegistry.java) and its builder, to create registry for metrics and specify exporter to be used for exporting snapshots.

See more about metrics here: [📘Metrics Details](metrics_details.md).

### Production Example

```java
public class Application {
    public static void main(String[] args) {
        Configuration configuration = ConfigurationBuilder.create()
                // init configuration
                .build();

        // create metrics registry without global labels
        MetricRegistry metricRegistry = MetricRegistry.builder()
                .discoverMetricProviders() // discover all metrics providers via SPI
                .discoverMetricsExporter(configuration) // discover single exporter factory via SPI
                //.setMetricsExporter(myDefaultExporter) // or set default exporter programmatically
                .build();

        // pass metrics registry to required classes to retrieve or register metrics
        MyModuleService service = new MyModuleService();
        service.bindMetrics(metricRegistry);

        // Application logic...
    }
}

// Each module can register some metrics by implementing MetricsRegistrationProvider SPI
class MyModuleMetricsProvider implements MetricsRegistrationProvider {

    public static final MetricKey<LongCounter> REQUESTS_COUNTER_KEY = MetricKey.of("requests", LongCounter.class);

    @Override
    public Collection<Metric.Builder<?, ?>> getMetricsToRegister(Configuration configuration) {
        // provide metrics to register
        return List.of(
                LongCounter.builder(REQUESTS_COUNTER_KEY)
                    .setDescription("Total number of requests")
                    .addDynamicLabelNames("method", "path")
        );
    }
}

// service class that uses the metric registered above
class MyModuleService implements MetricsBinder {

    private LongCounter requestsCounter;

    @Override
    public void bindMetrics(MetricRegistry registry) {
        // retrieve existing metric by its key
        this.requestsCounter = registry.getMetric(MyModuleMetrics.REQUESTS_COUNTER_KEY);
    }

    public void handleRequest(HttpMethod method, String path) {
        // observe metric
        requestsCounter.getOrCreateLabeled("method", method.toString(), "path", path).increment();
    }
}
```

```java
// module-info.java
module my.module {
    requires org.hiero.metrics.core;

    provides org.hiero.metrics.api.core.MetricsRegistrationProvider with
            org.hiero.metrics.MyModuleMetricsProvider;
}
```

```gradle
// gradle build.gradle of the application
mainModuleInfo {
    // add open metrics http endpoint exporter so it can be discovered via SPI
    runtimeOnly("org.hiero.metrics.openmetrics.http")
}
```
