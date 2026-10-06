// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.doIfNotInterrupted;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * A {@link UtilOp} that repeatedly runs freshly sourced operations until the selected logs contain
 * the given text or regex, or a timeout elapses.
 *
 * <p>The op source must create fresh operations on each invocation, since many
 * {@link SpecOperation} implementations are stateful and are not safe to reuse.
 */
public class UntilLogContainsOp extends UtilOp {
    private static final Logger log = LogManager.getLogger(UntilLogContainsOp.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration DEFAULT_BACKOFF_SLEEP = Duration.ofSeconds(1);
    private static final int DEFAULT_MAX_PENDING_OPS = Integer.MAX_VALUE;

    private final NodeSelector selector;
    private final LogContainmentCondition condition;
    private final Function<HapiSpec, SpecOperation[]> opSource;

    private Duration timeout = DEFAULT_TIMEOUT;
    private Duration pollInterval = DEFAULT_POLL_INTERVAL;
    private Duration backoffSleep = DEFAULT_BACKOFF_SLEEP;
    private IntSupplier maxPendingOpsSupplier = () -> DEFAULT_MAX_PENDING_OPS;
    private boolean loggingOff = false;

    public UntilLogContainsOp(
            @NonNull final NodeSelector selector,
            @NonNull final ExternalPath path,
            @Nullable final String text,
            @Nullable final Pattern pattern,
            @NonNull final Supplier<SpecOperation[]> opSource) {
        this(selector, path, text, pattern, ignore -> opSource.get());
    }

    public UntilLogContainsOp(
            @NonNull final NodeSelector selector,
            @NonNull final ExternalPath path,
            @Nullable final String text,
            @Nullable final Pattern pattern,
            @NonNull final Function<HapiSpec, SpecOperation[]> opSource) {
        this.selector = requireNonNull(selector);
        this.condition = new LogContainmentCondition(path, text, pattern);
        this.opSource = requireNonNull(opSource);
    }

    public UntilLogContainsOp exposingMatchGroupTo(final int group, @NonNull final AtomicReference<String> ref) {
        condition.exposingMatchGroupTo(group, ref);
        return this;
    }

    public UntilLogContainsOp lasting(@NonNull final Duration timeout) {
        this.timeout = requireStrictlyPositive(timeout, "timeout");
        return this;
    }

    public UntilLogContainsOp lasting(final long duration, @NonNull final java.util.concurrent.TimeUnit unit) {
        return lasting(Duration.ofMillis(requireNonNull(unit).toMillis(duration)));
    }

    public UntilLogContainsOp pollingEvery(@NonNull final Duration pollInterval) {
        this.pollInterval = requireNonNegative(pollInterval, "pollInterval");
        return this;
    }

    public UntilLogContainsOp pollingEvery(final long duration, @NonNull final java.util.concurrent.TimeUnit unit) {
        return pollingEvery(Duration.ofMillis(requireNonNull(unit).toMillis(duration)));
    }

    public UntilLogContainsOp maxPendingOps(final int maxPendingOps) {
        if (maxPendingOps < 1) {
            throw new IllegalArgumentException("maxPendingOps must be at least one");
        }
        return maxPendingOps(() -> maxPendingOps);
    }

    public UntilLogContainsOp maxPendingOps(@NonNull final IntSupplier maxPendingOpsSupplier) {
        this.maxPendingOpsSupplier = requireNonNull(maxPendingOpsSupplier);
        return this;
    }

    public UntilLogContainsOp backoffSleep(@NonNull final Duration backoffSleep) {
        this.backoffSleep = requireNonNegative(backoffSleep, "backoffSleep");
        return this;
    }

    public UntilLogContainsOp loggingOff() {
        this.loggingOff = true;
        return this;
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var nodes = spec.targetNetworkOrThrow().nodesFor(selector);
        final var start = Instant.now();
        final var deadline = start.plus(timeout);
        var nextPollTime = Instant.EPOCH;
        long submittedOps = 0;
        long submittedBatches = 0;

        if (!loggingOff) {
            log.info(
                    "Running ops until '{}' appears in {} within {}",
                    condition.searchTerm(),
                    condition.path(),
                    timeout);
        }

        while (Instant.now().isBefore(deadline)) {
            final var now = Instant.now();
            if (!now.isBefore(nextPollTime)) {
                if (condition.isSatisfiedBy(nodes)) {
                    logSuccess(start, submittedOps, submittedBatches);
                    return false;
                }
                nextPollTime = now.plus(pollInterval);
            }

            final int maxPendingOps = maxPendingOpsSupplier.getAsInt();
            if (maxPendingOps < 1) {
                throw new IllegalArgumentException("maxPendingOps supplier must return at least one");
            }
            final int numPendingOps = spec.numPendingOps();
            if (numPendingOps >= maxPendingOps) {
                if (!loggingOff) {
                    log.warn(
                            "Now {} ops pending; backing off for {} ms before rechecking {}",
                            numPendingOps,
                            backoffSleep.toMillis(),
                            condition.path());
                }
                pauseFor(min(remainingUntil(deadline), backoffSleep));
                continue;
            }

            final var ops = requireNonNull(opSource.apply(spec), "opSource returned null");
            if (ops.length == 0) {
                pauseFor(min(remainingUntil(deadline), Duration.between(Instant.now(), nextPollTime)));
                continue;
            }

            allRunFor(spec, ops);
            submittedOps += ops.length;
            submittedBatches++;
        }

        if (condition.isSatisfiedBy(nodes)) {
            logSuccess(start, submittedOps, submittedBatches);
            return false;
        }

        Assertions.fail(String.format(
                "Timed out after %s waiting for '%s' in %s after submitting %d ops across %d batches",
                timeout, condition.searchTerm(), condition.path(), submittedOps, submittedBatches));
        return false;
    }

    private void logSuccess(@NonNull final Instant start, final long submittedOps, final long submittedBatches) {
        if (!loggingOff) {
            log.info(
                    "Observed '{}' in {} after {} ({} ops across {} batches)",
                    condition.searchTerm(),
                    condition.path(),
                    Duration.between(start, Instant.now()),
                    submittedOps,
                    submittedBatches);
        }
    }

    private static Duration remainingUntil(@NonNull final Instant deadline) {
        return Duration.between(Instant.now(), deadline);
    }

    private static Duration min(@NonNull final Duration first, @NonNull final Duration second) {
        if (first.isNegative() || first.isZero()) {
            return Duration.ZERO;
        }
        if (second.isNegative() || second.isZero()) {
            return Duration.ZERO;
        }
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static void pauseFor(@NonNull final Duration duration) {
        if (!duration.isZero() && !duration.isNegative()) {
            doIfNotInterrupted(() -> MILLISECONDS.sleep(duration.toMillis()));
        }
    }

    private static Duration requireStrictlyPositive(@NonNull final Duration duration, @NonNull final String name) {
        requireNonNull(duration);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static Duration requireNonNegative(@NonNull final Duration duration, @NonNull final String name) {
        requireNonNull(duration);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }
}
