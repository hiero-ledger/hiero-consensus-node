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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * A {@link UtilOp} that validates that a node's log contains two patterns appearing in order
 * within a specified timeframe, with a time gap between them that falls within {@code [minGap, maxGap]}.
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
    private Instant candidateFirstTime = null;

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
            final var result = new SearchResult();
            spec.targetNetworkOrThrow().nodesFor(selector).forEach(node -> {
                searchNodeLog(node.getExternalPath(path), startTime, endTime, result);
            });

            if (result.matched) {
                log.info(
                        "Found matching pair: '{}' at {} and '{}' at {} (gap={})",
                        firstPattern,
                        result.firstTime,
                        secondPattern,
                        result.secondTime,
                        Duration.between(result.firstTime, result.secondTime));
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

        return false;
    }

    private void searchNodeLog(
            @NonNull final java.nio.file.Path logPath,
            @NonNull final Instant startTime,
            @NonNull final Instant endTime,
            @NonNull final SearchResult result) {
        if (result.matched) {
            return;
        }
        long newLinesRead = 0;
        try (BufferedReader reader = Files.newBufferedReader(logPath)) {
            try (var linesStream = reader.lines().skip(linesProcessed.get())) {
                final var iterator = linesStream.iterator();
                while (iterator.hasNext()) {
                    final String line = iterator.next();
                    newLinesRead++;

                    final Instant logInstant;
                    try {
                        if (line.length() < 23) continue;
                        final String timestamp = line.substring(0, 23);
                        final LocalDateTime logTime = LocalDateTime.parse(timestamp, LOG_TIMESTAMP_FORMAT);
                        logInstant = logTime.atZone(ZoneId.systemDefault()).toInstant();
                    } catch (Exception e) {
                        continue;
                    }

                    if (!logInstant.isAfter(startTime) || !logInstant.isBefore(endTime)) {
                        continue;
                    }

                    // Check for firstPattern — always update candidate to the latest match
                    if (line.contains(firstPattern)) {
                        candidateFirstTime = logInstant;
                    }
                    // Check for secondPattern only if we have a candidate firstPattern
                    if (candidateFirstTime != null && line.contains(secondPattern)) {
                        final Duration gap = Duration.between(candidateFirstTime, logInstant);
                        if (gap.compareTo(minGap) >= 0 && gap.compareTo(maxGap) <= 0) {
                            result.matched = true;
                            result.firstTime = candidateFirstTime;
                            result.secondTime = logInstant;
                            break;
                        }
                        // Gap didn't qualify — reset candidate so we look for the next pair
                        candidateFirstTime = null;
                    }
                }
            }
        } catch (NoSuchFileException nsfe) {
            log.warn("Log file not found: {}. Will retry.", logPath);
        } catch (Exception e) {
            log.error("Error reading log file {}", logPath, e);
            throw new RuntimeException("Error during log processing for " + logPath, e);
        }
        linesProcessed.addAndGet(newLinesRead);
    }

    /** Mutable holder for the search result across nodes. */
    private static class SearchResult {
        boolean matched;
        Instant firstTime;
        Instant secondTime;
    }
}
