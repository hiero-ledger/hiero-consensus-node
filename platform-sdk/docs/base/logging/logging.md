[⇧ Platform Base](../base.md)

## Hedera Logging API

> [!WARNING]
> The API is currently in develpment and is not optimized for production use.
> It is subject to change without notice.

### Introduction

The Hedera Logging API is a custom logging API which is crafted to meet our unique requirements, ensuring compatibility with various software integrations.
In addition, it acts as a bridge, redirecting log events from e.g. [SLF4J](https://www.slf4j.org) to our library, providing flexibility for all dependencies used in the project.
Our goal was to create a logging library that is lightweight, efficient, and easy to use, while also being highly performant and flexible.

### Architecture

As any project from the base team, the logging library is split into a public API and a private implementation which provides the basics for logging.
In addition, we enhanced the functionality of the library by introducing extension points, allowing for the creation of e.g. custom logging handlers.
Also, we aimed not to reinvent the wheel, so we decided to use common standards for using the API as you probably know from other logging libraries.

The architecture does not dictate the format of logging output, allowing for a wide range of output options.
This flexibility permits various configurations, from console outputs to integrations with systems like Graylog or Kibana.

#### Features of the Logging Facade

The library goes beyond mere message logging, encapsulating rich information within each logging event.
Key features include:

1. Support for Message Placeholders
2. Event Origin Tracking (limited to the class)
3. Timestamp Inclusion
4. Thread Identification
5. Throwable Cause Handling
6. Support for Various Log Levels
7. Marker Support
8. Key-Value Based Metadata
9. Simple property based configuration
10. Test Support

---

## Using the Hedera Logging API

### Setting Up the Logger

To integrate the new logging API within your Java application, begin by establishing a logger instance in your class.
Here’s how you can set it up:

1. **Import the Logger Class**:
   Start by importing the `Loggers` class from the `com.swirlds.logging.api` package.

   ```java
   import com.swirlds.logging.api.Loggers;
   ```
2. **Create a Logger Instance**:
   In your class, declare a `private static final` logger instance. Use the `Loggers.getLogger()` method, passing your class (`MyClass.class`) as a parameter.

   ```java
   public class MyClass {
       private static final Logger logger = Loggers.getLogger(MyClass.class);
   }
   ```

   This statement creates a logger specifically for `MyClass`, facilitating targeted logging within this class.

### Logging Messages

Once you have set up the logger, you can proceed to log messages.
The new API provides a straightforward and flexible way to log information.
Here’s how to use it:

1. **Basic Logging**:
   For a simple log message, use the `info` method with a string argument.

   ```java
   logger.info("Hello, world!");
   ```
2. **Logging with Placeholders**:
   The API supports placeholder syntax for dynamic message composition.
   Use curly braces `{}` as placeholders and pass the dynamic values as additional arguments.

   ```java
   logger.info("Hello, {}!", "world");
   ```

   In this example, the `{}` placeholder in the string will be replaced by `"world"`, resulting in the log message `"Hello, world!"`.

## Custom Logging Configuration

The configuration syntax is designed for clarity and ease of use.
It allows setting a global default logging level and specific levels for packages or classes, with an option to introduce filters for markers.

For practicality, the `logging.properties` file will reload every second, ensuring up-to-date logging configurations at all times.

The default search path for the properties file is the current working directory. It can be modified by setting the `LOG_CONFIG_PATH` environment variable.

The format of the logging configuration is user-friendly and self-explanatory.

### Levels

Here's an example representation of the syntax for setting logging levels:

```properties
# Global default logging level
logging.level = INFO

# Specific logging levels for packages or classes
logging.level.com.swirlds.common.crypto = DEBUG
logging.level.org.hiero.base.crypto.Signature = WARN
logging.level.com.hashgraph = WARN
```

This configuration demonstrates how to set a global default logging level (`INFO`).
It also illustrates how to specify logging levels for packages and classes.
For example, everything under `com.swirlds.common.crypto` is set to `DEBUG`, except for `org.hiero.base.crypto.Signature`, which is explicitly set to `WARN`.
Similarly, all loggers within `com.hashgraph` default to `WARN`.
This approach ensures that loggers inherit the most specific level defined in the configuration, providing both flexibility and precision in logging management.

### Markers

To provide developers with more control and flexibility in logging, the configuration supports filters for markers.
This feature allows developers to focus on log messages associated with specific markers, regardless of the logger's log level.
Here's a sample configuration for marker filters:

```properties
# Marker filter configuration
logging.marker.CONFIG = ENABLED
logging.marker.CRYPTO = DISABLED
logging.marker.OTHER = DEFAULT
```

In this configuration, the `logging.marker.NAME` pattern is used, where `NAME` represents the marker's name, and the value can be set to `ENABLED`, `DISABLED`, or `DEFAULT`.
For example, `logging.marker.CONFIG = ENABLED` would ensure that all log messages tagged with the `CONFIG` marker are displayed, irrespective of their log level.

### Example Configuration

A typical logging configuration file, incorporating these marker filters, might look like this:

```properties
# General logging level configuration
logging.level = INFO
logging.level.com.swirlds.common.crypto = DEBUG
logging.level.org.hiero.base.crypto.Signature = WARN
logging.level.com.hashgraph = WARN

# Marker-specific logging configuration
logging.marker.CONFIG = ENABLED
```

### Update the configuration at runtime

The logging framework will automatically reload the configuration file periodically.
By default, the configuration file is reloaded every 10 seconds.
This can be changed by setting the `logging.reloadConfigPeriod` property in the configuration file.
A reload will only update levels and markers, all other properties (like new handlers) will be ignored today.

## Handlers

### Introduction

To further refine our logging system, we have incorporated handlers for more granular control over logging behavior.
Each handler can be distinctly named and configured using the prefix `logging.handler.NAME`, where `NAME` serves as a unique identifier.
Two fields are required: `logging.handler.NAME.type`, to specify the type of the handler, and `logging.handler.NAME.enabled` must be set to `true` to activate the handler. The default value for all `logging.handler.NAME.enabled` properties is `false`.
This structure allows for the application of handler-specific settings.
For instance, `logging.handler.NAME.level` is used to set the logging level for a specific handler, ensuring that all previously discussed features such as marker filters and log level settings are compatible.
This setup is instrumental in creating dedicated log files or outputs for specific types of log messages, offering a focused view that is particularly useful in complex systems or during targeted analyses.

A key aspect of the Logging System design is its performance, emphasizing maximum efficiency with as low an impact as possible on the running system.

A particularly useful feature of handlers is the `inheritLevels` property.
This boolean property determines whether the handler should inherit the default level configurations set globally.
By default, `inheritLevels` is set to `true`.
However, it can be turned off to create a handler that focuses exclusively on a specific aspect of logging, such as a particular marker.
For example, if you need a handler that only logs entries marked with the `CRYPTO` marker, your configuration would look like this:

```properties
# Handler specific for CRYPTO marker
logging.handler.CRYPTO_FILE.enabled = true
logging.handler.CRYPTO_FILE.type = file
logging.handler.CRYPTO_FILE.inheritLevels = false
logging.handler.CRYPTO_FILE.level = OFF
logging.handler.CRYPTO_FILE.marker.CRYPTO = ENABLED
```

In this configuration, the `CRYPTO_FILE` handler is set to ignore the global log level settings (`level = OFF`) but is specifically enabled to log messages tagged with the `CRYPTO` marker (`marker.CRYPTO = ENABLED`).

### File Handler

Configure your file handlers with these properties to control logging behavior:

- **File Path (`logging.handler.NAME.file`)**: Specifies the file to write logs to.
- **Append Mode (`logging.handler.NAME.append`)**: If `true` (default), logs are appended to the file; if `false`, the file is overwritten on new logs.

**Examples:**

```yaml
logging.handler.NAME.file: /path/to/logfile.log
logging.handler.NAME.append: true
```

> [!NOTE]
> Adjust `NAME` to your handler's name. The default settings are optimized for general use.

---

## Test Support with `WithLoggingMirror`

To improve testing, we've introduced the `WithLoggingMirror` annotation in JUnit 5.
This annotation injects a `LoggingMirror` into test methods or classes, providing an isolated environment for logging event analysis.
It ensures that tests with this annotation do not run in parallel, maintaining the integrity of each test's logging data.
For more information see the [Test Support](../test-support/test-support.md) documentation.

A simple example of how to use this annotation is shown below:

```java
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.test.fixtures.LoggingMirror;
import com.swirlds.logging.test.fixtures.WithLoggingMirror;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@WithLoggingMirror
public class LoggersTest {

    @Inject
    LoggingMirror loggingMirror;

    @Test
    void loggingMirrorTest() {
        // given
        final var clazz = LoggersTest.class;
        final var logger = Loggers.getLogger(clazz);

        // when
        logger.error("test");

        // then
        Assertions.assertEquals(1, loggingMirror.getEvents().size());
        final LogEvent event = loggingMirror.getEvents().get(0);
        Assertions.assertEquals(clazz.getName(), event.loggerName());
        Assertions.assertEquals(Thread.currentThread().getName(), event.threadName());
        Assertions.assertEquals(Level.ERROR, event.level());
    }
}
```
