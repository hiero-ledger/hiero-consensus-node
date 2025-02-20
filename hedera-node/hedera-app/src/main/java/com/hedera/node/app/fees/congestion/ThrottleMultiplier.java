// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees.congestion;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hapi.utils.throttles.CongestibleThrottle;
import com.hedera.node.config.types.CongestionMultipliers;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Implementation responsible for determining the congestion multiplier based on
 * the sustained utilization of one or more {@link CongestibleThrottle}.
 */
public class ThrottleMultiplier {
    private static final Logger logger = LogManager.getLogger(ThrottleMultiplier.class);
    private static final long DEFAULT_MULTIPLIER = 1L;
    private static final Instant[] NO_CONGESTION_STARTS = new Instant[0];
    private final Supplier<CongestionMultipliers> multiplierSupplier;
    private final String congestionType;
    private final String usageType;
    private final String abbrevUsageType;
    private final LongSupplier minCongestionPeriodSupplier;
    private final Supplier<List<? extends CongestibleThrottle>> throttleSource;

    private long multiplier = DEFAULT_MULTIPLIER;
    private long previousMultiplier = DEFAULT_MULTIPLIER;
    private CongestionMultipliers activeConfig = null;
    private List<? extends CongestibleThrottle> activeThrottles = Collections.emptyList();
    private long[][] activeTriggerValues = {};
    private Instant[] congestionLevelStarts = NO_CONGESTION_STARTS;

    public ThrottleMultiplier(
            @NonNull final String usageType,
            @NonNull final String abbrevUsageType,
            @NonNull final String congestionType,
            @NonNull final LongSupplier minCongestionPeriodSupplier,
            @NonNull final Supplier<CongestionMultipliers> multiplierSupplier,
            @NonNull final Supplier<List<? extends CongestibleThrottle>> throttleSource) {
        this.usageType = requireNonNull(usageType, "usageType must not be null");
        this.abbrevUsageType = requireNonNull(abbrevUsageType, "abbrevUsageType must not be null");
        this.congestionType = requireNonNull(congestionType, "congestionType must not be null");
        this.minCongestionPeriodSupplier =
                requireNonNull(minCongestionPeriodSupplier, "minCongestionPeriodSupplier must not be null");
        this.multiplierSupplier = requireNonNull(multiplierSupplier, "multiplierSupplier must not be null");
        this.throttleSource = requireNonNull(throttleSource, "throttleSource must not be null");
    }

    /**
     * Updates the congestion multiplier for the given consensus time.
     *
     * @param consensusTime the consensus time
     */
    public void updateMultiplier(@NonNull final Instant consensusTime) {
        if (ensureConfigUpToDate()) {
            rebuildState();
        }

        long x = maxMultiplierOfActiveConfig(activeConfig.multipliers());
        updateCongestionLevelStartsWith(activeConfig.multipliers(), x, consensusTime);
        long minPeriod = minCongestionPeriodSupplier.getAsLong();
        multiplier = highestMultiplierNotShorterThan(activeConfig.multipliers(), minPeriod, consensusTime);

        if (multiplier != previousMultiplier) {
            logMultiplierChange(previousMultiplier, multiplier);
        }
        previousMultiplier = multiplier;
    }

    /**
     * Returns the current congestion multiplier.
     *
     * @return current congestion multiplier
     */
    public long currentMultiplier() {
        return multiplier;
    }

    /**
     * Rebuilds the object's internal state based on its dependencies expectations.
     * Must be called every time when the suppliers {@code throttleSource} or {@code multiplierSupplier} are updated.
     */
    public void resetExpectations() {
        activeThrottles = throttleSource.get();
        if (activeThrottles.isEmpty()) {
            logger.warn(
                    "Throttle multiplier for {} congestion has no throttle buckets, "
                            + "fee multiplier will remain at one!",
                    congestionType);
        }
        ensureConfigUpToDate();
        rebuildState();
    }

    /**
     * Resets the congestion level starts to the given values.
     *
     * @param startTimes the saved congestion level starts
     */
    public void resetCongestionLevelStarts(@NonNull final Instant[] startTimes) {
        congestionLevelStarts = startTimes.clone();
    }

    /**
     * Returns the congestion level starts.
     *
     * @return the congestion level starts
     */
    @NonNull
    public Instant[] congestionLevelStarts() {
        return congestionLevelStarts.clone();
    }

    @Override
    @NonNull
    public String toString() {
        if (activeConfig == null) {
            return " <N/A>";
        }
        var sb = new StringBuilder();
        long[] multipliers = activeConfig.multipliers();
        for (int i = 0, n = activeThrottles.size(); i < n; i++) {
            var throttle = activeThrottles.get(i);
            sb.append("\n  (")
                    .append(throttle.name())
                    .append(") When ")
                    .append(usageType)
                    .append(" exceeds:\n");
            for (int j = 0; j < multipliers.length; j++) {
                sb.append("    ")
                        .append(readableTpsCutoffFor(activeTriggerValues[i][j], throttle.mtps(), throttle.capacity()))
                        .append(" ")
                        .append(abbrevUsageType)
                        .append(", multiplier is ")
                        .append(multipliers[j])
                        .append("x")
                        .append((j == multipliers.length - 1) ? "" : "\n");
            }
        }
        return sb.toString();
    }

    @NonNull
    private String readableTpsCutoffFor(long capacityCutoff, long mtps, long capacity) {
        return String.format("%.2f", (capacityCutoff * 1.0) / capacity * mtps / 1000.0);
    }

    private boolean ensureConfigUpToDate() {
        var currConfig = multiplierSupplier.get();
        if (!currConfig.equals(activeConfig)) {
            activeConfig = currConfig;
            return true;
        }
        return false;
    }

    private void rebuildState() {
        int n = activeThrottles.size();
        int[] triggers = activeConfig.usagePercentTriggers();
        long[] multipliers = activeConfig.multipliers();
        activeTriggerValues = new long[n][multipliers.length];
        for (int i = 0; i < n; i++) {
            var throttle = activeThrottles.get(i);
            long capacity = throttle.capacity();
            for (int j = 0; j < triggers.length; j++) {
                long cutoff = (capacity / 100L) * triggers[j];
                activeTriggerValues[i][j] = cutoff;
            }
        }

        congestionLevelStarts = new Instant[multipliers.length];

        logReadableCutoffs();
    }

    private long maxMultiplierOfActiveConfig(@NonNull final long[] multipliers) {
        long max = DEFAULT_MULTIPLIER;
        for (int i = 0; i < activeTriggerValues.length; i++) {
            long used = activeThrottles.get(i).used();
            for (int j = 0; j < multipliers.length; j++) {
                if (used >= activeTriggerValues[i][j]) {
                    max = Math.max(max, multipliers[j]);
                }
            }
        }
        return max;
    }

    private void updateCongestionLevelStartsWith(
            @NonNull final long[] multipliers, long x, @NonNull final Instant consensusNow) {
        for (int i = 0; i < multipliers.length; i++) {
            if (x < multipliers[i]) {
                congestionLevelStarts[i] = null;
            } else if (congestionLevelStarts[i] == null) {
                congestionLevelStarts[i] = consensusNow;
            }
        }
    }

    /* Use the highest multiplier whose congestion level we have
    stayed above for at least the minimum number of seconds. */
    private long highestMultiplierNotShorterThan(
            @NonNull final long[] multipliers, final long period, @NonNull final Instant consensusNow) {
        multiplier = DEFAULT_MULTIPLIER;
        for (int i = multipliers.length - 1; i >= 0; i--) {
            var levelStart = congestionLevelStarts[i];
            if (levelStart != null) {
                long secsAtLevel = Duration.between(levelStart, consensusNow).getSeconds();
                if (secsAtLevel >= period) {
                    multiplier = multipliers[i];
                    break;
                }
            }
        }
        return multiplier;
    }

    private void logReadableCutoffs() {
        logger.info("The new cutoffs for {} congestion pricing are : {}", congestionType, this);
    }

    public void logMultiplierChange(long prev, long cur) {
        if (prev == DEFAULT_MULTIPLIER) {
            logger.info("Congestion pricing beginning w/ {}x multiplier", cur);
        } else {
            if (cur > prev) {
                logger.info("Congestion pricing continuing, reached {}x multiplier", cur);
            } else if (cur == DEFAULT_MULTIPLIER) {
                logger.info("Congestion pricing ended");
            }
        }
    }
}
