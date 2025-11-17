## Metrics Details

[📘Back to Overview](metrics_overview.md)

### Metric API

All metrics interfaces available to use are defined in `org.hiero.metrics.api` package.<br/>
Each interface has a builder class to configure and create an instance of the metric with specific implementation,
that is hidden from the client. Any builder requires a [MetricKey](../src/main/java/org/hiero/metrics/api/core/MetricKey.java) to be provided,
that identifies metric with name and class of metric interface (see [Metric Registry](#metric-registry) for more details).

Main metric interface is [Metric](../src/main/java/org/hiero/metrics/api/core/Metric.java)
which provides methods to access metric metadata and information about labels of the metric, defined during metric creation.
Metric may have zero or more _constant_ labels that already have a value and will be attached to all data points of the metric.
Metric may also have zero or more _dynamic_ labels, names of which are defined during metric creation
and values are defined when accessing a specific data point of the metric for observation.
Metric names and labels must be of pattern `^[a-zA-Z_][a-zA-Z0-9_]*$`.

There are two main extensions of the Metric interface:
- [StatelessMetric](../src/main/java/org/hiero/metrics/api/StatelessMetric.java)<br/>
Terminal metric that does not provide methods to observe/update data points - only access/read them.
Updates for such metrics are done via external code. Examples are JVM memory usage metric, CPU usage metric, etc.
- [StatefulMetric](../src/main/java/org/hiero/metrics/api/core/StatefulMetric.java)<br/>
This is not a terminal metric (has an abstract builder) which stores data points per unique combination of dynamic label values.
It knows its data point type and how to instantiate it, so if new dynamic label values are provided,
it will create a new data point and return it to the client for observation, otherwise return existing data point.
Stateful metric also knows data point default initializer - an object used to initialize newly created data points (like initial value),
but API also allows to provide custom initializer when accessing specific data point.
If no dynamic labels are defined for the metric, then it will have a single data point, which also will be created lazily on first access.

### Metric Types

Supported metric types are defined by enum [MetricType](../src/main/java/org/hiero/metrics/api/core/MetricType.java)
Here is the table of all metrics available to use:

|                                       Metric                                       |   Type   |                                                            Description                                                             |
|------------------------------------------------------------------------------------|----------|------------------------------------------------------------------------------------------------------------------------------------|
| [LongCounter](../src/main/java/org/hiero/metrics/api/LongCounter.java)             | Counter  | Allows to increment `long` (only increasing), should be used also for lower types like `integer`.                                  |
| [DoubleCounter](../src/main/java/org/hiero/metrics/api/DoubleCounter.java)         | Counter  | Allows to increment `double` (only increasing).                                                                                    |
| [StatelessMetric](../src/main/java/org/hiero/metrics/api/StatelessMetric.java)     | Gauge    | Requires zero ore more `DoubleSupplier` (with optional lables), which will be called on export.                                    |
| [BooleanGauge](../src/main/java/org/hiero/metrics/api/BooleanGauge.java)           | Gauge    | Allows to set single `boolean` value.                                                                                              |
| [LongGauge](../src/main/java/org/hiero/metrics/api/LongGauge.java)                 | Gauge    | Gauge to observe and store `long`. May have an accumulator applied to previous and observed values.                                |
| [DoubleGauge](../src/main/java/org/hiero/metrics/api/DoubleGauge.java)             | Gauge    | Gauge to observe and store `double`. May have an accumulator applied to previous and observed values.                              |
| [GenericGauge](../src/main/java/org/hiero/metrics/api/GenericGauge.java)           | Gauge    | Parametrized generic gauge that allows to observe any non-primitive type and has a converter to `double` or `long` for exporting.  |
| [GaugeAdapter](../src/main/java/org/hiero/metrics/api/GaugeAdapter.java)           | Gauge    | A gauge to adapt to any external class that is used to observe and hold a single value. Parametrized with external datapoint type. |
| [StatsGaugeAdapter](../src/main/java/org/hiero/metrics/api/StatsGaugeAdapter.java) | Gauge    | Similar to GaugeAdapter, but for multiple numeric values - usually different stats on same observation.                            |
| [StateSet](../src/main/java/org/hiero/metrics/api/StateSet.java)                   | StateSet | Allows for enabling/disabling states defined by enum type.                                                                         |

There is also package `org.hiero.metrics.api.stat` that contains custom statistical metrics build on top of the above metrics.

### Metric Registry

[MetricRegistry](../src/main/java/org/hiero/metrics/api/core/MetricRegistry.java) can be used to register new metrics and retrieve existing ones. 
Metrics Registry can have immutable list of global labels, which will be applied to all registered metrics.
Registry can also be managed by [MetricsExportManager](../src/main/java/org/hiero/metrics/api/export/MetricsExportManager.java)
to export all its registered metrics - see more in [Metrics Exporting](metrics_exporting.md) documentation.

There are two ways to register metrics in the registry:
- Programmatically - by using metric builders and calling `register` method of the registry.
This way is usually used when metrics are created and used only in specific limited scope of the application.
- Declaratively - by implementing [MetricsRegistrationProvider](../src/main/java/org/hiero/metrics/api/core/MetricsRegistrationProvider.java)
interface (providing implementation via SPI) and calling `registerMetrics` method of the registry.

Metrics Registry cannot have two metrics with the same name.
To identify a metric in the registry a [MetricKey](../src/main/java/org/hiero/metrics/api/core/MetricKey.java) should be used,
which contains metric name and class of the metric interface, which is used to validate metric type and cast to required metric interface when retrieving by key.

`MetricsRegistry.Builder` is used to create instances of Metric Registry.
It's `discoverMetricProviders()` method allows discover all available `MetricsRegistrationProvider`s via SPI and register provided metrics.

[MetricsBinder](../src/main/java/org/hiero/metrics/api/core/MetricsBinder.java) can be used to bind/propagate metrics to any external class.
Application classes can implement this interface to register metrics in the registry or retrieve metrics by `MetricKey`s for observations.
Metric registry should be explicitly provided to the binder.

### Tips and Tricks

1. Use global labels in Metric Registry only if they cannot be added during metrics ingestion.
   Usually ingesters like OTEL Collector are able to attach environment or instance labels to all metrics, when collecting them.
2. **DO NOT** use high-cardinality values (like IDs, hashes, timestamps, etc.) for dynamic labels of the metric.
3. API allows to create multiple metric registries, but usually one registry per application is enough.
4. Metric must be registered once in a registry and may be use in different places in the code.
   Use [MetricsRegistrationProvider](../src/main/java/org/hiero/metrics/api/core/MetricsRegistrationProvider.java) for metrics registration
   and [MetricsBinder](../src/main/java/org/hiero/metrics/api/core/MetricsBinder.java) to propagate registry and access metrics and their data points in application classes.
5. When metric has dynamic labels, try to cache and reuse String label names and supply them in alphabetical order to access labeled data points.
   Moreover, labeled data point also can be cached, if it is used frequently and label values are known.

[📘Back to Overview](metrics_overview.md)
