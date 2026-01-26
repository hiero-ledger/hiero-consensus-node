This module implements an HTTP server that exposes application metrics in the [OpenMetrics 1.0](https://github.com/prometheus/OpenMetrics/blob/main/specification/OpenMetrics.md) text format.

To expose metrics via JDK HTTP server, add this module to your project's module path.
In tests, it can be added runtime dependency:

```gradle
runtimeOnly("org.hiero.metrics.openmetrics.httpserver")
```

[OpenMetricsHttpServerConfig](src/main/java/org/hiero/metrics/openmetrics/config/OpenMetricsHttpServerConfig.java) class provides configuration options for the HTTP server.
