// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.turnLoggingOff;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static java.lang.Thread.onSpinWait;
import static java.lang.Thread.sleep;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.common.base.Stopwatch;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ProviderRun extends UtilOp {
    private static final Logger log = LogManager.getLogger(ProviderRun.class);

    private static final int DEFAULT_MAX_OPS_PER_SEC = Integer.MAX_VALUE;
    private static final int DEFAULT_MAX_PENDING_OPS = Integer.MAX_VALUE;
    private static final int DEFAULT_BACKLOG_SLEEPOFF_SECS = 1;
    private static final long DEFAULT_DURATION = 30;
    private static final TimeUnit DEFAULT_UNIT = TimeUnit.SECONDS;
    private static final int DEFAULT_TOTAL_OPS_TO_SUBMIT = -1;

    private final Function<HapiSpec, OpProvider> providerFn;
    private IntSupplier maxOpsPerSecSupplier = () -> DEFAULT_MAX_OPS_PER_SEC;
    private IntSupplier maxPendingOpsSupplier = () -> DEFAULT_MAX_PENDING_OPS;
    private IntSupplier backoffSleepSecsSupplier = () -> DEFAULT_BACKLOG_SLEEPOFF_SECS;
    private LongSupplier durationSupplier = () -> DEFAULT_DURATION;
    private Supplier<TimeUnit> unitSupplier = () -> DEFAULT_UNIT;
    private IntSupplier totalOpsToSubmit = () -> DEFAULT_TOTAL_OPS_TO_SUBMIT;
    private boolean loggingOff = false;
    private boolean waitForPendingOps = false;
    private static final Duration WAIT_PENDING_OPS_TIMEOUT = Duration.ofSeconds(10);

    private Optional<BiConsumer<EnumMap<ResponseCodeEnum, AtomicInteger>, EnumMap<ResponseCodeEnum, AtomicInteger>>>
            statusCountAsserter = Optional.empty();

    public ProviderRun assertStatusCounts(
            BiConsumer<EnumMap<ResponseCodeEnum, AtomicInteger>, EnumMap<ResponseCodeEnum, AtomicInteger>> asserter) {
        statusCountAsserter = Optional.of(asserter);
        return this;
    }

    private final Map<HederaFunctionality, AtomicInteger> counts = new HashMap<>();

    public ProviderRun(Function<HapiSpec, OpProvider> providerFn) {
        this.providerFn = providerFn;
        Stream.of(HederaFunctionality.class.getEnumConstants()).forEach(type -> counts.put(type, new AtomicInteger()));
    }

    public ProviderRun lasting(final long duration, final TimeUnit unit) {
        this.unitSupplier = () -> unit;
        this.durationSupplier = () -> duration;
        return this;
    }

    public ProviderRun lasting(LongSupplier durationSupplier, Supplier<TimeUnit> unitSupplier) {
        this.unitSupplier = unitSupplier;
        this.durationSupplier = durationSupplier;
        return this;
    }

    public ProviderRun loggingOff() {
        this.loggingOff = true;
        return this;
    }

    public ProviderRun maxOpsPerSec(final int maxOpsPerSec) {
        this.maxOpsPerSecSupplier = () -> maxOpsPerSec;
        return this;
    }

    public ProviderRun maxOpsPerSec(IntSupplier maxOpsPerSecSupplier) {
        this.maxOpsPerSecSupplier = maxOpsPerSecSupplier;
        return this;
    }

    public ProviderRun maxPendingOps(IntSupplier maxPendingOpsSupplier) {
        this.maxPendingOpsSupplier = maxPendingOpsSupplier;
        return this;
    }

    public ProviderRun backoffSleepSecs(IntSupplier backoffSleepSecsSupplier) {
        this.backoffSleepSecsSupplier = backoffSleepSecsSupplier;
        return this;
    }

    public ProviderRun waitForPendingOps() {
        this.waitForPendingOps = true;
        return this;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) {
        int MAX_N = Runtime.getRuntime().availableProcessors();
        int MAX_OPS_PER_SEC = maxOpsPerSecSupplier.getAsInt();
        int MAX_PENDING_OPS = maxPendingOpsSupplier.getAsInt();
        int BACKOFF_SLEEP_SECS = backoffSleepSecsSupplier.getAsInt();
        long duration = durationSupplier.getAsLong();
        OpProvider provider = providerFn.apply(spec);

        allRunFor(spec, provider.suggestedInitializers().toArray(new HapiSpecOperation[0]));
        if (!loggingOff) {
            log.info("Finished initialization for provider run...");
        }

        TimeUnit unit = unitSupplier.get();
        Stopwatch stopwatch = Stopwatch.createStarted();

        final var remainingOpsToSubmit = new AtomicInteger(totalOpsToSubmit.getAsInt());
        final boolean fixedOpSubmission = (remainingOpsToSubmit.get() < 0) ? false : true;
        int submittedSoFar = 0;
        long durationMs = unit.toMillis(duration);
        long logIncrementMs = durationMs / 100;
        long nextLogTargetMs = logIncrementMs;
        long lastDeltaLogged = -1;
        final var opsThisSecond = new AtomicInteger(0);
        final var submissionBoundaryMs = new AtomicLong(stopwatch.elapsed(MILLISECONDS) + 1_000);
        while (stopwatch.elapsed(unit) < duration) {
            long elapsedMs = stopwatch.elapsed(MILLISECONDS);
            if (elapsedMs > submissionBoundaryMs.get()) {
                submissionBoundaryMs.getAndAdd(1_000);
                opsThisSecond.set(0);
            }
            int numPending = spec.numPendingOps();
            if (elapsedMs > nextLogTargetMs) {
                nextLogTargetMs += logIncrementMs;
                long delta = duration - stopwatch.elapsed(unit);
                if (delta != lastDeltaLogged && !loggingOff) {
                    String message = String.format(
                            "%d %s%s left in test - %d ops submitted so far (%d pending).",
                            delta,
                            unit.toString().toLowerCase(),
                            (fixedOpSubmission ? (" or " + remainingOpsToSubmit + " ops ") : ""),
                            submittedSoFar,
                            numPending);
                    log.info(message);
                    log.info("Precheck txn status counts :: {}", spec.precheckStatusCounts());
                    log.info("Resolved txn status counts :: {}", spec.finalizedStatusCounts());
                    log.info("\n------------------------------\n");
                    lastDeltaLogged = delta;
                }
            }

            if (fixedOpSubmission && remainingOpsToSubmit.get() <= 0) {
                if (numPending > 0) {
                    continue;
                }
                if (!loggingOff) {
                    log.info("Finished submission of total {} operations", totalOpsToSubmit.getAsInt());
                }
                break;
            }
            if (numPending < MAX_PENDING_OPS) {
                HapiSpecOperation[] burst = IntStream.range(
                                0,
                                Math.min(
                                        MAX_N,
                                        fixedOpSubmission
                                                ? Math.min(
                                                        remainingOpsToSubmit.get(),
                                                        MAX_OPS_PER_SEC - opsThisSecond.get())
                                                : MAX_OPS_PER_SEC - opsThisSecond.get()))
                        .mapToObj(ignore -> provider.get())
                        .flatMap(Optional::stream)
                        .peek(op -> {
                            counts.get(op.type()).getAndIncrement();
                            if (loggingOff) {
                                turnLoggingOff(op);
                            }
                        })
                        .toArray(HapiSpecOperation[]::new);
                if (burst.length > 0) {
                    allRunFor(spec, inParallel(burst));
                    submittedSoFar += burst.length;
                    if (fixedOpSubmission) {
                        remainingOpsToSubmit.getAndAdd(-burst.length);
                    }
                    opsThisSecond.getAndAdd(burst.length);
                }
            } else {
                log.warn("Now {} ops pending; backing off for {}s!", numPending, BACKOFF_SLEEP_SECS);
                try {
                    sleep(BACKOFF_SLEEP_SECS * 1_000L);
                } catch (InterruptedException ignore) {
                }
            }
        }

        if (waitForPendingOps) {
            if (!loggingOff) {
                log.info("Waiting for {} pending ops to finish...", spec.numPendingOps());
            }
            long beginWait = Instant.now().toEpochMilli();
            while (spec.numPendingOps() > 0
                    || (Instant.now().toEpochMilli() - beginWait) < WAIT_PENDING_OPS_TIMEOUT.toMillis()) {
                onSpinWait();
            }
        }

        Map<HederaFunctionality, Integer> finalCounts = counts.entrySet().stream()
                .filter(entry -> entry.getValue().get() > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().get()));
        log.info("Final breakdown of *provided* ops: {}", finalCounts);
        log.info("Final breakdown of *resolved* statuses: {}", spec.finalizedStatusCounts());

        statusCountAsserter.ifPresent(statusCountsBiConsumer -> statusCountsBiConsumer.accept(
                (EnumMap<ResponseCodeEnum, AtomicInteger>) spec.precheckStatusCounts(),
                (EnumMap<ResponseCodeEnum, AtomicInteger>) spec.finalizedStatusCounts()));

        return false;
    }
}
