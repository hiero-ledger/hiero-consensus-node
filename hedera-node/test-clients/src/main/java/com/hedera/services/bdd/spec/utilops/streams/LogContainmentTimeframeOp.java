// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.doIfNotInterrupted;
import static com.swirlds.common.io.utility.FileUtils.rethrowIO;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;

/**
 * A {@link UtilOp} that validates that the selected nodes' application or platform log contains
 * a sequence of patterns within a specified timeframe.
 */
public class LogContainmentTimeframeOp extends UtilOp {
    private static final DateTimeFormatter LOG_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final NodeSelector selector;
    private final ExternalPath path;
    private final List<String> patterns;
    private final Supplier<Instant> startTimeSupplier;
    private final Duration timeframe;
    private final Duration waitTimeout;

    public LogContainmentTimeframeOp(
            @NonNull final NodeSelector selector,
            @NonNull final ExternalPath path,
            @NonNull final List<String> patterns,
            @NonNull final Supplier<Instant> startTimeSupplier,
            @NonNull final Duration timeframe,
            @NonNull final Duration waitTimeout) {
        if (path != ExternalPath.APPLICATION_LOG && path != ExternalPath.SWIRLDS_LOG) {
            throw new IllegalArgumentException(path + " is not a log");
        }
        this.path = requireNonNull(path);
        this.patterns = requireNonNull(patterns);
        this.selector = requireNonNull(selector);
        this.startTimeSupplier = requireNonNull(startTimeSupplier);
        this.timeframe = requireNonNull(timeframe);
        this.waitTimeout = requireNonNull(waitTimeout);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        // Get the start time when the operation is actually executed
        final Instant startTime = startTimeSupplier.get();
        if (startTime == null) {
            throw new IllegalStateException("Start time supplier returned null");
        }

        final Instant timeoutDeadline = Instant.now().plus(waitTimeout);
        List<String> missingPatterns = null;

        while (Instant.now().isBefore(timeoutDeadline)) {
            missingPatterns = checkLogsForPatterns(spec, startTime);
            if (missingPatterns.isEmpty()) {
                return false; // Success - all patterns found
            }

            // Not all patterns found yet, wait and retry if there's time left
            if (Instant.now().isBefore(timeoutDeadline)) {
                doIfNotInterrupted(() -> MILLISECONDS.sleep(1000));
            }
        }

        // If we get here, we timed out without finding all patterns
        Assertions.fail(String.format(
                "Did not find all expected log patterns within timeout period of %s. Missing patterns: %s",
                waitTimeout, String.join(", ", missingPatterns)));

        return false;
    }

    private List<String> checkLogsForPatterns(@NonNull final HapiSpec spec, @NonNull final Instant startTime) {
        List<String> missingPatterns = new ArrayList<>();

        spec.targetNetworkOrThrow().nodesFor(selector).forEach(node -> {
            final var logContents = rethrowIO(() -> Files.readString(node.getExternalPath(path)));
            final var logLines = logContents.split("\n");

            // Filter logs to only those within the timeframe
            List<String> relevantLogs = new ArrayList<>();
            for (String line : logLines) {
                String timestamp = line.substring(0, 23); // "2025-03-17 21:36:20.275"
                LocalDateTime logTime = LocalDateTime.parse(timestamp, LOG_TIMESTAMP_FORMAT);
                Instant logInstant = logTime.atZone(ZoneId.systemDefault()).toInstant();

                if (logInstant.isAfter(startTime) && logInstant.isBefore(startTime.plus(timeframe))) {
                    relevantLogs.add(line);
                }
            }

            // Check each pattern appears in order
            int lastFoundIndex = -1;
            for (String pattern : patterns) {
                boolean found = false;
                for (int i = lastFoundIndex + 1; i < relevantLogs.size(); i++) {
                    String log = relevantLogs.get(i);
                    // Strip timestamp and thread info for comparison
                    String logContent = log.replaceAll(
                            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\w+\\s+\\d+\\s+\\w+\\s+-\\s+\\[.*?\\]\\s*",
                            "");
                    if (logContent.contains(pattern)) {
                        found = true;
                        lastFoundIndex = i;
                        break;
                    }
                }
                if (!found && !missingPatterns.contains(pattern)) {
                    missingPatterns.add(pattern);
                }
            }
        });

        return missingPatterns;
    }
}
