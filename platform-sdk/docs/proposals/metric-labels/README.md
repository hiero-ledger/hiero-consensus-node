# Metric labels

| Metadata           | Entities                                   | 
 |--------------------|--------------------------------------------|
 | Designers          | [@hendrikebbers](https://github.com/hendrikebbers), [@mxtartaglia-sl](https://github.com/mxtartaglia-sl) |


## Summary

This proposal describes a new feature for the metrics module.
The feature is called "metric labels" and allows to add labels to metrics.
Labels are key-value pairs that can be used to filter and group metrics.
Why this proposal uses the name "metric labels" (like prometheus does) other libraries (like micrometer or open telemetry) use the term tags or attributes.

## Motivation

Today the metrics module only supports the definition of metrics without adding custom labels.
While each metric has a hardcoded category, name, and nodeId no other custom information can be added.
This proposal suggests adding a new concept called "metric labels" to the metrics module and
migrate the `nodeId` information to a label.
By doing so the metrics module becomes more flexible and can be used 100% independent of the
platform module since it does not depend on the platform module's `nodeId` anymore.

## Goals

- Add a new concept called "metric labels" to the metrics module
- Migrate the `nodeId` information to a label
- Make the metrics module independent of the platform module
- Provide a public API to define labels for metrics
- Add labels to the metrics snapshot API
- The output of the metrics snapshot API should contain the labels
- The output of the metrics snapshot API must be compatible with the current output (no breaking changes)
- Create better and more dynamic dashboards in Grafana by using labels

## Non-Goals

Even with the new concept the metrics api should be only used in our project code (including projects like block-node, ...).
The metrics module should not be used by the end user.
Here the preferred solution is still that we forward our metrics to a monitoring system that the custom application code uses.

## Design & Architecture

This chapter describes the changes that are needed to add labels to the metrics module.

### About labels

Before we start with the implementation we need to define what labels are.
Labels allow for additional dimensions within a metric.
Labels are key-value pairs that are used to define, filter and group metrics.
Labels are used by monitoring systems like Grafana to create more dynamic dashboards.

In addition to having a unique name, a metric can also have labels. Labels allow for differentiation within a metric.
For example, a metric measuring incoming connections can distinguish between GET and POST requests using labels.

Consider the following example with the metric `api_http_requests_total`, which counts the total number of HTTP requests a server receives. 

To distinguish between different types of requests, such as GET and POST requests, one could define two different metrics and aggregate them at query time. Alternatively, one could define possible labels for a single metric when creating it and assign specific values to these labels based on the nature of the HTTP request at measurement time. 

Then, using the Prometheus query language:
* Sum up the values of all metrics with that name (as always):
`sum(api_http_requests_total) ` 

* Or,  get the sums of the values measured with the corresponding label information.
`sum(api_http_requests_total{method="GET"})`
`sum(api_http_requests_total{method="POST"})`

See https://prometheus.io/docs/prometheus/latest/querying/basics/#time-series-selectors for more information.

In prometheus a concrete metric is defined like this (see https://prometheus.io/docs/concepts/data_model/#notation):

```
api_http_requests_total{method="POST", handler="/messages"}
```

Here a metric is defined by a name (`api_http_requests_total`) and a set of labels (`method="POST"` and `handler="/messages"`).
In prometheus that defines the unique identifier of a metric.

When using labels to create dashboards in Grafana prometheus provides a rich query language to filter and group metrics: https://prometheus.io/docs/prometheus/latest/querying/examples/

We will use the same practices and definition as in prometheus regarding labels (see https://prometheus.io/docs/practices/naming/#labels and https://prometheus.io/docs/practices/instrumentation/#use-labels).

### Public API

The metrics module will be extended by a new data type called `Label`.
A label is a key-value pair that can be defined as a `record`:

```java
record Label(@NonNull String key, @NonNull String value) {}
``` 

The `Metric` interface will be extended by several methods that allow to define label values to a metric:

```java
interface Metric {
    
    //...
    
    @NonNull
    Metric withLabel(@NonNull String key, @NonNull String value);
    
    @NonNull
    Metric withLabel(@NonNull Label label);
    
    @NonNull
    Metric withLabels(@NonNull Label... labels);
    
    @NonNull
    Set<Label> getLabels();
    
    @NonNull
    Set<String> getLabelKeys();
}
```

By doing so the api can be used like this:

```java
    metric.withLabel("label1", "123")
        .withLabel("label2", "456")
        .inc(); 
```

The shown code would throw an exception if the metric does not support the labels `label1` and `label2`.

Like the name or category, the label keys are defined when a metric is created and cannot be changed later.
Based on that the MetricConfig class will be extended as shown:

```java
abstract class MetricConfig {
    
    //...
    
    /**
    * Returns a config with the given label keys next to the already defined labels.
    */
    @NonNull
    MetricConfig withLabels(@NonNull String... labelKeys) {...}
    
    /**
    * Returns a config with the given label keys next to the already defined labels.
    */
    @NonNull
    MetricConfig withLabels(@NonNull Set<String> labelKeys) {...}
    
    /**
    * Returns a config with the given predefined label next to the already defined labels.
    */
    @NonNull
    MetricConfig withPredefinedLabel(@NonNull String key, @NonNull String value) {...}
    
    /**
    * Returns a config with the given predefined label next to the already defined labels.
    */
    @NonNull
    MetricConfig withPredefinedLabel(@NonNull Label label) {...}
    
    /**
    * Returns a set of all supported label keys.
    */
    @NonNull 
    Set<String> getLabelKeys();
    
    /**
    * Returns a set of all predefined labels.
    */
    @NonNull 
    Set<Label> getPredefinedLabels();
}
```

Next on that we need to modify the `Metrics` interface to support labels:

```java

interface Metrics {
    
    // old methods that will be removed:
    // Object getValue(@NonNull String category, @NonNull String name);
    
    @Nullable
    Object getValue(@NonNull String category, @NonNull String name, @NonNull Set<Label> labels);
}
```

Since the old method is only used in tests or demos today we can do that change easily.

The `Metrics` interface contains some methods to remove metrics. Here different variants of the method could make sense
when adding metrics. Since the methods are not used at all we will not add any additional methods to remove metrics.
If we see that additional methods are needed we can add them later.
The methods to remove metrics will have an extended JavaDoc and will be defined like this:

```java
interface Metrics {
    
    //...
    
    /**
    * Removes all metrics with the given category and name. Labels will be ignored.
    */
    void remove(@NonNull String category, @NonNull String name);
}
```

> [!NOTE]  
Since most of the filter methods that the `Metrics` interface defines today are not used we will not introduce any additional filter methods in this proposal.
If we see that additional methods are needed we can add them later.

We will have labels that are global for a `Metrics` instance.
The best example is the `nodeId` label.
We do not want to add the `nodeId` label to the creation of each metric.
Instead of that we want to add the `nodeId` label to the `Metrics` instance.
That should happen when a `Metrics` instance is created.
The creation of a `Metrics` instance is not public api today.
With the implementation of this proposal a `MetricsFactory` interface should be introduced that allows to create a `Metrics` instance.
The `MetricsFactory` interface will be defined like this:

```java
interface MetricsFactory {
    
    @NonNull
    default Metrics create() {
        return create(Set.of());
    }
    
    @NonNull
    Metrics create(@NonNull Set<Label> defaultLabels);
}
```

The definition of the api should follow our rules regarding services as defined at https://github.com/hashgraph/hedera-services/blob/main/platform-sdk/docs/base/service-architecture/service-architecture.md

##### Examples

Here are some examples how to define a metric with labels:

```java
final Label transactionTypeLabel = new Label("transactionType", "fileUpload");
final Counter.Config config = new Counter.Config("transactionsCategory", "transactionCount").withLabels("transactionType");

final Metrics metrics = ...;
final Counter counter = metrics.getOrCreate(config);

counter.withLabel(transactionTypeLabel).increment();
```

In prometheus the metric will be defined like this:

```
transactionsCategory.transactionCount{transactionType="fileUpload"}
```

#### Snapshot API

The snapshot API will be extended to return the labels of a metric for a snapshot.
By doing so any monitoring system that uses the snapshot API can use the labels.
Today we only have 2 system that uses the snapshot API: the prometheus endpoint and the CSV exporter.
Since no class of the snapshot API is public today (based on the internal usage of `nodeId`) we plan to move parts
of the snapshot API to the public API.

### Migration

Next to extending the public API we need to migrate the current metrics to the new concept.

#### Migration of the `nodeId`

The `nodeId` information of a metric will be migrated to labels.
Today we create a prometheus label out of the `nodeId` internally in our prometheus endpoint implementation.
The same conversion can be used to create a label for the `nodeId` in future but should be moved from the prometheus endpoint to the platform code.
The key of a metric won't change and still be defined as the combination of the `category` and `name`.
The unique identifier of the metric is the combination key and the labels.
That is a breaking change since today the key is the unique identifier of a metric.
Since we do not have an api today to add labels the migration should be minimalistic.

#### Migration of the prometheus endpoint

The `nodeId` of a platform is already added as a label today when using the prometheus endpoint.
For that endpoint we just need to remove the hard coded `nodeId` label and add a general algorithm to add labels.

#### Migration of the CSV exporter

The CSV exporter use the `nodeId` today to create the file name.
Since the CSV exporter is only used by the platform module we can continue with that approach.

Let's assume we would have the following metrics:

| Type           | Name   | Category   | labels | Value |
 |---------------|--------|------------|-------|-------|
 | Counter          | myCounter1 | private_category | nodeId=1 | 1 |
 | Counter          | myCounter2 | private_category | nodeId=1 | 1 |
 | Counter          | myCounter3 | private_category | nodeId=1 | 1 |
 | Counter          | myCounter4 | private_category | nodeId=1, foo=bar | 1 |
 | DoubleGauge | myDoubleGauge | public_category | nodeId=1 | 1 |
 | DoubleGauge | myDoubleGauge | private_category | nodeId=1, foo=bar | 1 |
        

Today a CSV file is created like this:
```
filename:,/var/folders/d8/16q746f12zq6tb1ngwngp8gh0000gn/T/junit15732516610471683378/MainNetStats42.csv,
myCounter1:,myCounter1,
myCounter2:,myCounter2,
myCounter3:,myCounter3,
myCounter4:,myCounter4,
myDoubleGauge:,myDoubleGauge,
myDoubleGauge:,myDoubleGauge,

,,private_category,private_category,private_category,private_category,public_category,private_category,
,,myCounter1,myCounter2,myCounter3,myCounter4,myDoubleGauge,myDoubleGauge,
,,1,2,3,4,1.230,4.560,
```

The first section of the list is metadata that defines the file name (that includes the `nodeId`) and a list of all metric names.
The metric category is ignored here. As you can see, the two gauges with the same name but different categories are printed twice in the same line.
This proposal would not change that behavior.

The second section of the list defines the metrics and their values. This part will be changed to include the labels.

```
,,private_category,private_category,private_category,private_category,public_category,
,,myCounter1,myCounter2,myCounter3,myCounter4,myDoubleGauge,
,,1,2,3,4,1.230,4.560,
```

Here the first row defines the category of the metric and the second row the name. The 3rd row contains the values.
An additional row between the categories and values will be added to include the labels of the metric.
Since a metric can contain multiple labels the cell content must be quoted:

```
,,private_category,private_category,private_category,private_category,public_category,private_category,
,,myCounter1,myCounter2,myCounter3,myCounter4,myDoubleGauge,myDoubleGauge,
,,"nodeId=1","nodeId=1","nodeId=1","nodeId=1, foo=bar","nodeId=1","nodeId=1, foo=bar",
,,1,2,3,4,1.230,4.560,
```

## Testing

The metrics module is tested by unit tests today. We need to add tests for the new concept of labels.
Next to that we need to add tests of the CSV exporter and the prometheus endpoint to ensure that the labels are added correctly.
Here we need to check that a platform metric (that has a `nodeId` label) is still working correctly and ends in the exact same output as today.

## Alternatives

Since the idea of this proposal is to make the metrics module independent of the platform module we need to remove
the usage of the `NodeId` class or extract the class from the platform and define it as general API.
Since the class does not make any sense outside the consensus node (like in block node for example) this is not a good idea.
Another option would be to change the type of the `nodeId` to a `String` but that would end in an ugly API for any
system next to the platform that uses the metrics module since `null` or any other default must always be used as the `nodeId`.
Next to that all that ideas will end in having a hardcoded `nodeId` label in the metrics that will be forwarded to the monitoring system.

## Future Work

As a next step we can think about labels next to `nodeId` that makes sense to have in our metrics.
For example, we can add a label called `type` that defines if a metric is a counter, gauge, or histogram.
Another option is to add labels for layers (like `base`, `consensus`, `services`, ...).
By doing so we can add more information to the metrics that can be used by the monitoring system.

## References

- https://prometheus.io/docs/practices/naming/#labels
- https://prometheus.io/docs/practices/instrumentation/#use-labels
- https://www.javadoc.io/doc/io.micrometer/micrometer-core/1.1.0/io/micrometer/core/instrument/Tag.html
- https://github.com/vert-x3/vertx-micrometer-metrics/blob/master/src/main/java/io/vertx/micrometer/Label.java
- https://geode.apache.org/docs/guide/115/tools_modules/micrometer/micrometer-meters.html
- https://prometheus.io/docs/prometheus/latest/querying/basics/#instant-vector-selectors
