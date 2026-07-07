// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle;
import com.hedera.node.app.spi.throttle.ScheduleThrottle;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The application's strategy for creating a {@link ScheduleThrottle} to use at consensus.
 */
public class AppScheduleThrottleFactory implements ScheduleThrottle.Factory {
    private static final Logger log = LogManager.getLogger(AppScheduleThrottleFactory.class);
    private final Supplier<State> stateSupplier;
    private final Supplier<Configuration> configSupplier;
    private final Supplier<ThrottleDefinitions> definitionsSupplier;
    private final ThrottleAccumulatorFactory throttleAccumulatorFactory;

    public interface ThrottleAccumulatorFactory {
        ThrottleAccumulator newThrottleAccumulator(
                @NonNull Supplier<Configuration> config,
                @NonNull IntSupplier capacitySplitSource,
                @NonNull ThrottleAccumulator.ThrottleType throttleType);
    }

    public AppScheduleThrottleFactory(
            @NonNull final Supplier<Configuration> configSupplier,
            @NonNull final Supplier<State> stateSupplier,
            @NonNull final Supplier<ThrottleDefinitions> definitionsSupplier,
            @NonNull final ThrottleAccumulatorFactory throttleAccumulatorFactory) {
        this.configSupplier = requireNonNull(configSupplier);
        this.stateSupplier = requireNonNull(stateSupplier);
        this.definitionsSupplier = requireNonNull(definitionsSupplier);
        this.throttleAccumulatorFactory = requireNonNull(throttleAccumulatorFactory);
    }

    @Override
    public ScheduleThrottle newScheduleThrottle(
            final int capacitySplit, @Nullable final ThrottleUsageSnapshots initialUsageSnapshots) {
        final var throttleAccumulator = throttleAccumulatorFactory.newThrottleAccumulator(
                configSupplier, () -> capacitySplit, ThrottleAccumulator.ThrottleType.BACKEND_THROTTLE);
        throttleAccumulator.applyGasConfig();
        throttleAccumulator.applyBytesConfig();
        throttleAccumulator.applyDurationConfig();
        throttleAccumulator.rebuildFor(definitionsSupplier.get());
        if (initialUsageSnapshots != null) {
            final var tpsUsageSnapshots = initialUsageSnapshots.tpsThrottles();
            // Usage snapshots are paired to throttles strictly by position, so a mismatch in counts
            // means the throttle definitions changed since the snapshot was taken and we cannot safely
            // restore usage by index. (The converse does not hold: a definitions change that keeps the
            // same total count still passes this check, since snapshots carry no bucket identity.)
            // Fail fast before mutating any throttle; the caller (ScheduleCreateHandler#upToDateThrottle)
            // recovers by rebuilding from scratch and replaying this second's scheduled transactions.
            ThrottleAccumulator.restoreThrottleUsage(
                    ThrottleAccumulator.selectedThrottlesFor(throttleAccumulator, tpsUsageSnapshots),
                    tpsUsageSnapshots,
                    ThrottleAccumulator.SnapshotMismatchPolicy.FAIL_FAST);
            throttleAccumulator.gasLimitThrottle().resetUsageTo(initialUsageSnapshots.gasThrottleOrThrow());
        }
        // Throttle.allow() has the opposite polarity of ThrottleAccumulator.checkAndEnforceThrottle()
        return new ScheduleThrottle() {
            @Override
            public boolean allow(
                    @NonNull final AccountID payerId,
                    @NonNull final TransactionBody body,
                    @NonNull final HederaFunctionality function,
                    @NonNull final Instant now) {
                return !throttleAccumulator.checkAndEnforceThrottle(
                        new TransactionInfo(
                                SignedTransaction.DEFAULT,
                                body,
                                TransactionID.DEFAULT,
                                payerId,
                                SignatureMap.DEFAULT,
                                Bytes.EMPTY,
                                function,
                                null),
                        now,
                        stateSupplier.get(),
                        null,
                        true);
            }

            @Override
            public ThrottleUsageSnapshots usageSnapshots() {
                return new ThrottleUsageSnapshots(
                        throttleAccumulator.allActiveThrottlesIncludingHighVolume().stream()
                                .map(DeterministicThrottle::usageSnapshot)
                                .toList(),
                        throttleAccumulator.gasLimitThrottle().usageSnapshot(),
                        throttleAccumulator.opsDurationThrottle().usageSnapshot());
            }
        };
    }
}
