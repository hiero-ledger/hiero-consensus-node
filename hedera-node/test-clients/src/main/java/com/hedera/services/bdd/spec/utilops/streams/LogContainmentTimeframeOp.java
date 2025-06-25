// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.doIfNotInterrupted;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * A {@link UtilOp} that validates that the selected nodes' application or platform log contains
 * a sequence of patterns within a specified timeframe, reading the log incrementally.
 */
public class LogContainmentTimeframeOp extends UtilOp {
    private static final Logger log = LogManager.getLogger(LogContainmentTimeframeOp.class);
    private static final DateTimeFormatter LOG_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final NodeSelector selector;
    private final ExternalPath path;
    private final List<String> originalPatterns;
    private final Supplier<Instant> startTimeSupplier;
    private final Duration timeframe;
    private final Duration waitTimeout;

    // State for incremental reading
    private final AtomicLong linesProcessed = new AtomicLong(0L);
    private final Set<String> foundPatterns = new HashSet<>();

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
        this.originalPatterns = new ArrayList<>(requireNonNull(patterns)); // Store original
        this.selector = requireNonNull(selector);
        this.startTimeSupplier = requireNonNull(startTimeSupplier);
        this.timeframe = requireNonNull(timeframe);
        this.waitTimeout = requireNonNull(waitTimeout);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final Instant startTime = startTimeSupplier.get();
        if (startTime == null) {
            throw new IllegalStateException("Start time supplier returned null");
        }
        final Instant endTime = startTime.plus(timeframe);
        final Instant timeoutDeadline = Instant.now().plus(waitTimeout);

        log.info(
                "Starting log check: StartTime={}, Timeframe={}, Timeout={}, TargetPatterns={}",
                startTime,
                timeframe,
                waitTimeout,
                originalPatterns);

        while (Instant.now().isBefore(timeoutDeadline)) {
            // Process new log lines for all selected nodes
            spec.targetNetworkOrThrow().nodesFor(selector).forEach(node -> {
                findNewPatternsInNodeLog(node.getExternalPath(path), startTime, endTime);
            });

            if (foundPatterns.size() == originalPatterns.size()) {
                log.info("All patterns found successfully.");
                return false; // Success
            }

            if (Instant.now().isBefore(timeoutDeadline)) {
                doIfNotInterrupted(() -> MILLISECONDS.sleep(1000));
            }
        }

        // Timeout occurred
        final List<String> missingPatterns = originalPatterns.stream()
                .filter(p -> !foundPatterns.contains(p))
                .collect(Collectors.toList());

        Assertions.fail(String.format(
                "Did not find all expected log patterns. StartTime=%s, Timeframe=%s, Timeout=%s. MissingPatterns=[%s]",
                startTime, timeframe, waitTimeout, String.join(", ", missingPatterns)));

        return false; // Should not be reached due to Assertions.fail
    }

    private void findNewPatternsInNodeLog(
            @NonNull final java.nio.file.Path logPath,
            @NonNull final Instant startTime,
            @NonNull final Instant endTime) {
        long newLinesRead = 0;
        try (BufferedReader reader = Files.newBufferedReader(logPath)) {
            // Skip lines already processed and process the rest
            try (var linesStream = reader.lines().skip(linesProcessed.get())) {
                List<String> remainingPatternsToCheck = originalPatterns.stream()
                        .filter(p -> !foundPatterns.contains(p))
                        .toList();

                if (remainingPatternsToCheck.isEmpty()) {
                    return; // All patterns already found, no need to read further for this node
                }

                final var iterator = linesStream.iterator();
                while (iterator.hasNext()) {
                    String line = iterator.next();
                    newLinesRead++;

                    LocalDateTime logTime;
                    Instant logInstant;
                    try {
                        // Basic check for timestamp format length
                        if (line.length() < 23) continue;
                        final String timestamp = line.substring(0, 23);
                        logTime = LocalDateTime.parse(timestamp, LOG_TIMESTAMP_FORMAT);
                        logInstant = logTime.atZone(ZoneId.systemDefault()).toInstant();
                    } catch (Exception e) {
                        continue;
                    }

                    // Check if the log entry is within the timeframe
                    if (logInstant.isAfter(startTime) && logInstant.isBefore(endTime)) {
                        // Check against patterns not yet found
                        for (final String pattern : remainingPatternsToCheck) {
                            if (line.contains(pattern)) {
                                log.debug("Found pattern '{}' in line: {}", pattern, line);
                                foundPatterns.add(pattern);
                                // Re-calculate remaining patterns if one was found
                                remainingPatternsToCheck = originalPatterns.stream()
                                        .filter(p -> !foundPatterns.contains(p))
                                        .toList();
                                if (remainingPatternsToCheck.isEmpty()) {
                                    break; // Stop checking this line if all patterns found
                                }
                            }
                        }
                    }
                    if (remainingPatternsToCheck.isEmpty()) {
                        break; // Stop processing new lines if all patterns found
                    }
                }
            }
        } catch (NoSuchFileException nsfe) {
            log.warn("Log file not found: {}. Will retry.", logPath);
            // File might appear later, do nothing and let the loop retry
        } catch (Exception e) {
            log.error("Error reading log file {}. Patterns checked so far: {}", logPath, foundPatterns, e);
            // Rethrow or handle as appropriate for the test framework
            throw new RuntimeException("Error during log processing for " + logPath, e);
        }
        // Update the total lines processed for this file
        linesProcessed.addAndGet(newLinesRead);
    }
}
