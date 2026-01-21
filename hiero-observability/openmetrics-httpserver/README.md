This module implements an HTTP server that exposes application metrics in the [OpenMetrics 1.0](https://github.com/prometheus/OpenMetrics/blob/main/specification/OpenMetrics.md) text format.

To expose metrics via JDK HTTP server, add module to your project as runtime dependency:

```gradle
// gradle build.gradle of the application
mainModuleInfo {
    runtimeOnly("org.hiero.metrics.openmetrics.httpserver")
}
```

[OpenMetricsHttpServerConfig](src/main/java/org/hiero/metrics/openmetrics/config/OpenMetricsHttpServerConfig.java) class provides configuration options for the HTTP server.
