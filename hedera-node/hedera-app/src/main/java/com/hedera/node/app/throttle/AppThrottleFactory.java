// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle;
import com.hedera.node.app.spi.throttle.Throttle;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The application's strategy for creating a {@link Throttle} to use at consensus.
 */
public class AppThrottleFactory implements Throttle.Factory {
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

    public AppThrottleFactory(
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
    public Throttle newThrottle(final int capacitySplit, @Nullable final ThrottleUsageSnapshots initialUsageSnapshots) {
        final var throttleAccumulator = throttleAccumulatorFactory.newThrottleAccumulator(
                configSupplier, () -> capacitySplit, ThrottleAccumulator.ThrottleType.BACKEND_THROTTLE);
        throttleAccumulator.applyGasConfig();
        throttleAccumulator.applyBytesConfig();
        throttleAccumulator.applyDurationConfig();
        throttleAccumulator.rebuildFor(definitionsSupplier.get());
        if (initialUsageSnapshots != null) {
            final var tpsThrottles = throttleAccumulator.allActiveThrottles();
            final var tpsUsageSnapshots = initialUsageSnapshots.tpsThrottles();
            for (int i = 0, n = tpsThrottles.size(); i < n; i++) {
                tpsThrottles.get(i).resetUsageTo(tpsUsageSnapshots.get(i));
            }
            throttleAccumulator.gasLimitThrottle().resetUsageTo(initialUsageSnapshots.gasThrottleOrThrow());
        }
        // Throttle.allow() has the opposite polarity of ThrottleAccumulator.checkAndEnforceThrottle()
        return new Throttle() {
            @Override
            public boolean allow(
                    @NonNull final AccountID payerId,
                    @NonNull final TransactionBody body,
                    @NonNull final HederaFunctionality function,
                    @NonNull final Instant now) {
                return !throttleAccumulator.checkAndEnforceThrottle(
                        new TransactionInfo(
                                Transaction.DEFAULT,
                                body,
                                TransactionID.DEFAULT,
                                payerId,
                                SignatureMap.DEFAULT,
                                Bytes.EMPTY,
                                function,
                                null),
                        now,
                        stateSupplier.get());
            }

            @Override
            public ThrottleUsageSnapshots usageSnapshots() {
                return new ThrottleUsageSnapshots(
                        throttleAccumulator.allActiveThrottles().stream()
                                .map(DeterministicThrottle::usageSnapshot)
                                .toList(),
                        throttleAccumulator.gasLimitThrottle().usageSnapshot(),
                        throttleAccumulator.opsDurationThrottle().usageSnapshot());
            }
        };
    }
}
