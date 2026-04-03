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
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * A {@link UtilOp} that validates that the selected nodes' application or platform log contains
 * two patterns appearing in order within a specified timeframe, with a time gap between them
 * that falls within {@code [minGap, maxGap]}, reading the log incrementally.
 *
 * <p>This is useful for asserting that a state transition (e.g. quiescence) lasted a meaningful
 * duration, distinguishing real transitions from transient flickers that resolve in milliseconds.
 */
public class LogContainmentPairTimeframeOp extends UtilOp {
    private static final Logger log = LogManager.getLogger(LogContainmentPairTimeframeOp.class);
    private static final DateTimeFormatter LOG_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final NodeSelector selector;
    private final ExternalPath path;
    private final String firstPattern;
    private final String secondPattern;
    private final Supplier<Instant> startTimeSupplier;
    private final Duration timeframe;
    private final Duration waitTimeout;
    private final Duration minGap;
    private final Duration maxGap;

    // State for incremental reading
    private final AtomicLong linesProcessed = new AtomicLong(0L);
    // Persists across polls so a firstPattern match on one poll can pair with a secondPattern on the next
    @Nullable
    private Instant candidateFirstTime;

    public LogContainmentPairTimeframeOp(
            @NonNull final NodeSelector selector,
            @NonNull final ExternalPath path,
            @NonNull final Supplier<Instant> startTimeSupplier,
            @NonNull final Duration timeframe,
            @NonNull final Duration waitTimeout,
            @NonNull final String firstPattern,
            @NonNull final String secondPattern,
            @NonNull final Duration minGap,
            @NonNull final Duration maxGap) {
        if (path != ExternalPath.APPLICATION_LOG
                && path != ExternalPath.BLOCK_NODE_COMMS_LOG
                && path != ExternalPath.SWIRLDS_LOG) {
            throw new IllegalArgumentException(path + " is not a log");
        }
        this.path = requireNonNull(path);
        this.selector = requireNonNull(selector);
        this.startTimeSupplier = requireNonNull(startTimeSupplier);
        this.timeframe = requireNonNull(timeframe);
        this.waitTimeout = requireNonNull(waitTimeout);
        this.firstPattern = requireNonNull(firstPattern);
        this.secondPattern = requireNonNull(secondPattern);
        this.minGap = requireNonNull(minGap);
        this.maxGap = requireNonNull(maxGap);
        if (minGap.compareTo(maxGap) > 0) {
            throw new IllegalArgumentException("minGap (" + minGap + ") must be <= maxGap (" + maxGap + ")");
        }
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
                "Starting paired log check: StartTime={}, Timeframe={}, Timeout={}, "
                        + "FirstPattern='{}', SecondPattern='{}', MinGap={}, MaxGap={}",
                startTime,
                timeframe,
                waitTimeout,
                firstPattern,
                secondPattern,
                minGap,
                maxGap);

        while (Instant.now().isBefore(timeoutDeadline)) {
            // Process new log lines for all selected nodes
            final boolean[] matched = {false};
            final Instant[] matchedTimes = new Instant[2];
            spec.targetNetworkOrThrow().nodesFor(selector).forEach(node -> {
                if (!matched[0]) {
                    findMatchingPairInNodeLog(node.getExternalPath(path), startTime, endTime, matched, matchedTimes);
                }
            });

            if (matched[0]) {
                log.info(
                        "Found matching pair: '{}' at {} and '{}' at {} (gap={})",
                        firstPattern,
                        matchedTimes[0],
                        secondPattern,
                        matchedTimes[1],
                        Duration.between(matchedTimes[0], matchedTimes[1]));
                return false; // Success
            }

            if (Instant.now().isBefore(timeoutDeadline)) {
                doIfNotInterrupted(() -> MILLISECONDS.sleep(1000));
            }
        }

        Assertions.fail(String.format(
                "Did not find a matching pair of log patterns within the timeframe. "
                        + "StartTime=%s, Timeframe=%s, Timeout=%s, FirstPattern='%s', SecondPattern='%s', "
                        + "MinGap=%s, MaxGap=%s",
                startTime, timeframe, waitTimeout, firstPattern, secondPattern, minGap, maxGap));

        return false; // Should not be reached due to Assertions.fail
    }

    private void findMatchingPairInNodeLog(
            @NonNull final java.nio.file.Path logPath,
            @NonNull final Instant startTime,
            @NonNull final Instant endTime,
            @NonNull final boolean[] matched,
            @NonNull final Instant[] matchedTimes) {
        long newLinesRead = 0;
        try (BufferedReader reader = Files.newBufferedReader(logPath)) {
            // Skip lines already processed and process the rest
            try (var linesStream = reader.lines().skip(linesProcessed.get())) {
                final var iterator = linesStream.iterator();
                while (iterator.hasNext()) {
                    final String line = iterator.next();
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
                        // Check for firstPattern — always update candidate to the latest match
                        if (line.contains(firstPattern)) {
                            candidateFirstTime = logInstant;
                        }
                        // Check for secondPattern only if we have a candidate firstPattern
                        if (candidateFirstTime != null && line.contains(secondPattern)) {
                            final Duration gap = Duration.between(candidateFirstTime, logInstant);
                            if (gap.compareTo(minGap) >= 0 && gap.compareTo(maxGap) <= 0) {
                                matched[0] = true;
                                matchedTimes[0] = candidateFirstTime;
                                matchedTimes[1] = logInstant;
                                break;
                            }
                            // Gap didn't qualify — reset candidate so we look for the next pair
                            candidateFirstTime = null;
                        }
                    }
                }
            }
        } catch (NoSuchFileException nsfe) {
            log.warn("Log file not found: {}. Will retry.", logPath);
            // File might appear later, do nothing and let the loop retry
        } catch (Exception e) {
            log.error("Error reading log file {}. Candidate so far: {}", logPath, candidateFirstTime, e);
            // Rethrow or handle as appropriate for the test framework
            throw new RuntimeException("Error during log processing for " + logPath, e);
        }
        // Update the total lines processed for this file
        linesProcessed.addAndGet(newLinesRead);
    }
}
