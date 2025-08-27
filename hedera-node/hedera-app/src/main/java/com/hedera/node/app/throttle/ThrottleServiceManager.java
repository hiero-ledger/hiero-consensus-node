// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.throttle.schemas.V0490CongestionThrottleSchema.CONGESTION_LEVEL_STARTS_STATE_KEY;
import static com.hedera.node.app.throttle.schemas.V0490CongestionThrottleSchema.THROTTLE_USAGE_SNAPSHOTS_STATE_KEY;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.congestion.CongestionLevelStarts;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.throttle.annotations.BackendThrottle;
import com.hedera.node.app.throttle.annotations.IngestThrottle;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Singleton
public class ThrottleServiceManager {
    private static final Logger log = LogManager.getLogger(ThrottleServiceManager.class);

    private final ThrottleParser throttleParser;
    private final ThrottleAccumulator ingestThrottle;
    private final ThrottleAccumulator backendThrottle;
    private final CongestionMultipliers congestionMultipliers;

    @Nullable
    private ThrottleDefinitions activeDefinitions;

    @Inject
    public ThrottleServiceManager(
            @NonNull final ThrottleParser throttleParser,
            @NonNull @IngestThrottle final ThrottleAccumulator ingestThrottle,
            @NonNull @BackendThrottle final ThrottleAccumulator backendThrottle,
            @NonNull final CongestionMultipliers congestionMultipliers) {
        this.throttleParser = throttleParser;
        this.ingestThrottle = requireNonNull(ingestThrottle);
        this.backendThrottle = requireNonNull(backendThrottle);
        this.congestionMultipliers = requireNonNull(congestionMultipliers);
    }

    /**
     * Completely rebuilds the throttling service from the given state.
     *
     * <p><b>IMPORTANT</b> - In the current system, the initialization
     * order below (first apply configuration, second rebuild throttles,
     * third reset multiplier expectations, final rehydrate internal state)
     * is actually necessary to avoid NPEs and other issues.
     *
     * @param state the state to use
     * @param throttleDefinitions the serialized throttle definitions
     */
    public void init(@NonNull final State state, @NonNull final Bytes throttleDefinitions) {
        requireNonNull(state);
        // Apply configuration for gas, bytes and ops duration throttles
        applyGasConfig();
        applyBytesConfig();
        applyOpsDurationConfig();
        // Create backend/frontend throttles from the configured system file
        rebuildThrottlesFrom(throttleDefinitions);
        // Reset multiplier expectations
        congestionMultipliers.resetExpectations();
        // Rehydrate the internal state of the throttling service (no-op if at genesis)
        final var serviceStates = state.getReadableStates(CongestionThrottleService.NAME);
        resetThrottlesFromUsageSnapshots(serviceStates);
        syncFromCongestionLevelStarts(serviceStates);
    }

    /**
     * Returns the throttle definitions that are currently active.
     * @return the active throttle definitions
     * @throws IllegalStateException if the active throttle definitions are not available
     */
    public @NonNull ThrottleDefinitions activeThrottleDefinitionsOrThrow() {
        return requireNonNull(activeDefinitions);
    }

    /**
     * Saves the current state of the throttles and congestion level starts
     * to the given state.
     *
     * @param state the state to save to
     */
    public void saveThrottleSnapshotsAndCongestionLevelStartsTo(@NonNull final State state) {
        requireNonNull(state);
        final var serviceStates = state.getWritableStates(CongestionThrottleService.NAME);
        saveThrottleSnapshotsTo(serviceStates);
        saveCongestionLevelStartsTo(serviceStates);
    }

    /**
     * Refreshes the parts of the throttle service that depend on the
     * current network configuration.
     */
    public void refreshThrottleConfiguration() {
        applyGasConfig();
        applyBytesConfig();
        applyOpsDurationConfig();
        congestionMultipliers.resetExpectations();
    }

    /**
     * Recreates the throttles based on the given throttle definitions.
     *
     * @param encoded the serialized throttle definitions
     * @return the success status to use if the update was via HAPI
     */
    public ResponseCodeEnum recreateThrottles(@NonNull final Bytes encoded) {
        final var validatedThrottles = rebuildThrottlesFrom(encoded);
        congestionMultipliers.resetExpectations();
        return validatedThrottles.successStatus();
    }

    public int numImplicitCreations(
            @NonNull final TransactionBody body, @NonNull final ReadableAccountStore accountStore) {
        return backendThrottle.getImplicitCreationsCount(body, accountStore);
    }

    public int numAutoAssociations(
            @NonNull final TransactionBody body, @NonNull final ReadableTokenRelationStore relationStore) {
        return backendThrottle.getAutoAssociationsCount(body, relationStore);
    }

    /**
     * Updates all metrics for the throttles.
     */
    public void updateAllMetrics() {
        ingestThrottle.updateAllMetrics();
        backendThrottle.updateAllMetrics();
    }

    private void saveThrottleSnapshotsTo(@NonNull final WritableStates serviceStates) {
        final var hapiThrottles = backendThrottle.allActiveThrottles();
        final List<ThrottleUsageSnapshot> hapiThrottleSnapshots;
        if (hapiThrottles.isEmpty()) {
            hapiThrottleSnapshots = emptyList();
        } else {
            hapiThrottleSnapshots = new ArrayList<>();
            for (final var throttle : hapiThrottles) {
                hapiThrottleSnapshots.add(throttle.usageSnapshot());
            }
        }

        final var gasThrottle = backendThrottle.gasLimitThrottle();
        final var gasThrottleSnapshot = gasThrottle.usageSnapshot();
        final var opsDurationThrottle = backendThrottle.opsDurationThrottle();
        final var opsDurationThrottleSnapshot = opsDurationThrottle.usageSnapshot();

        final WritableSingletonState<ThrottleUsageSnapshots> throttleSnapshots =
                serviceStates.getSingleton(THROTTLE_USAGE_SNAPSHOTS_STATE_KEY);
        throttleSnapshots.put(
                new ThrottleUsageSnapshots(hapiThrottleSnapshots, gasThrottleSnapshot, opsDurationThrottleSnapshot));
    }

    private void saveCongestionLevelStartsTo(@NonNull final WritableStates serviceStates) {
        final WritableSingletonState<CongestionLevelStarts> congestionLevelStarts =
                serviceStates.getSingleton(CONGESTION_LEVEL_STARTS_STATE_KEY);
        congestionLevelStarts.put(new CongestionLevelStarts(
                translateToList(congestionMultipliers.entityUtilizationCongestionStarts()),
                translateToList(congestionMultipliers.gasThrottleMultiplierCongestionStarts())));
    }

    private @NonNull ThrottleParser.ValidatedThrottles rebuildThrottlesFrom(@NonNull final Bytes encoded) {
        final var validatedThrottles = throttleParser.parse(encoded);
        ingestThrottle.rebuildFor(validatedThrottles.throttleDefinitions());
        backendThrottle.rebuildFor(validatedThrottles.throttleDefinitions());
        this.activeDefinitions = validatedThrottles.throttleDefinitions();
        return validatedThrottles;
    }

    private void applyGasConfig() {
        ingestThrottle.applyGasConfig();
        backendThrottle.applyGasConfig();
    }

    private void applyBytesConfig() {
        ingestThrottle.applyBytesConfig();
    }

    private void applyOpsDurationConfig() {
        backendThrottle.applyDurationConfig();
    }

    private void syncFromCongestionLevelStarts(@NonNull final ReadableStates serviceStates) {
        final var congestionStarts =
                CongestionStarts.from(serviceStates.getSingleton(CONGESTION_LEVEL_STARTS_STATE_KEY));
        // No matter if the congestion level starts are empty in state because
        // we're at genesis; or because that's the actual configuration, there's
        // nothing to do here.
        if (congestionStarts.cryptoTransferLevelStarts().length > 0) {
            congestionMultipliers.resetUtilizationScaledThrottleMultiplierStarts(
                    congestionStarts.cryptoTransferLevelStarts());
        }
        if (congestionStarts.gasLevelStarts().length > 0) {
            congestionMultipliers.resetGasThrottleMultiplierStarts(congestionStarts.gasLevelStarts());
        }
    }

    private void resetThrottlesFromUsageSnapshots(@NonNull final ReadableStates serviceStates) {
        final ReadableSingletonState<ThrottleUsageSnapshots> usageSnapshotsState =
                serviceStates.getSingleton(THROTTLE_USAGE_SNAPSHOTS_STATE_KEY);
        final var usageSnapshots = requireNonNull(usageSnapshotsState.get());
        safeResetThrottles(backendThrottle.allActiveThrottles(), usageSnapshots.tpsThrottles());
        if (usageSnapshots.hasGasThrottle()) {
            backendThrottle.gasLimitThrottle().resetUsageTo(usageSnapshots.gasThrottleOrThrow());
        }
        if (usageSnapshots.hasEvmOpsDurationThrottle()) {
            backendThrottle.opsDurationThrottle().resetUsageTo(usageSnapshots.evmOpsDurationThrottleOrThrow());
        }
    }

    public void resetThrottlesUnconditionally(@NonNull final ReadableStates serviceStates) {
        final ReadableSingletonState<ThrottleUsageSnapshots> usageSnapshotsState =
                serviceStates.getSingleton(THROTTLE_USAGE_SNAPSHOTS_STATE_KEY);
        final var usageSnapshots = requireNonNull(usageSnapshotsState.get());
        resetUnconditionally(backendThrottle.allActiveThrottles(), usageSnapshots.tpsThrottles());
        if (usageSnapshots.hasGasThrottle()) {
            backendThrottle.gasLimitThrottle().resetUsageTo(usageSnapshots.gasThrottleOrThrow());
        }
        if (usageSnapshots.hasEvmOpsDurationThrottle()) {
            backendThrottle.opsDurationThrottle().resetUsageTo(usageSnapshots.evmOpsDurationThrottleOrThrow());
        }
    }

    /**
     * Reclaims the capacity used for throttling the given number of implicit creations or auto associations
     * on the frontend.
     *
     * @param numCapacity the number of implicit creations or auto associations
     */
    public void reclaimFrontendThrottleCapacity(final int numCapacity, final HederaFunctionality hederaFunctionality) {
        try {
            ingestThrottle.leakCapacityForNOfUnscaled(numCapacity, hederaFunctionality);
        } catch (Exception ignore) {
            // Ignore if the frontend bucket has already leaked all the capacity
            // used for throttling the transaction on the frontend
        }
    }

    // override hashCode()/equals() for array fields
    @SuppressWarnings("java:S6218")
    private record CongestionStarts(Instant[] cryptoTransferLevelStarts, Instant[] gasLevelStarts) {
        static CongestionStarts from(
                @NonNull final ReadableSingletonState<CongestionLevelStarts> congestionLevelStarts) {
            final var sourceStarts = requireNonNull(congestionLevelStarts.get());
            return new CongestionStarts(
                    asMultiplierStarts(sourceStarts.genericLevelStarts()),
                    asMultiplierStarts(sourceStarts.gasLevelStarts()));
        }
    }

    private static @NonNull List<Timestamp> translateToList(@NonNull final Instant[] levelStartTimes) {
        final List<Timestamp> list = new ArrayList<>(levelStartTimes.length);
        for (final var startTime : levelStartTimes) {
            list.add(startTime == null ? EPOCH : new Timestamp(startTime.getEpochSecond(), startTime.getNano()));
        }
        return list;
    }

    private static void safeResetThrottles(
            final List<DeterministicThrottle> throttles, final List<ThrottleUsageSnapshot> snapshots) {
        // No-op if we don't have a snapshot for every throttle
        if (throttles.size() != snapshots.size()) {
            return;
        }
        final var currentSnapshots =
                throttles.stream().map(DeterministicThrottle::usageSnapshot).toList();
        for (int i = 0, n = throttles.size(); i < n; i++) {
            try {
                throttles.get(i).resetUsageTo(snapshots.get(i));
            } catch (final Exception e) {
                log.warn(
                        "Saved usage snapshot @ index {} was not compatible with the corresponding"
                                + " active throttle ({}), not performing a reset !",
                        i,
                        e.getMessage());
                resetUnconditionally(throttles, currentSnapshots);
                break;
            }
        }
    }

    private static void resetUnconditionally(
            final List<DeterministicThrottle> throttles, final List<ThrottleUsageSnapshot> knownCompatible) {
        for (int i = 0, n = knownCompatible.size(); i < n; i++) {
            throttles.get(i).resetUsageTo(knownCompatible.get(i));
        }
    }

    private static @NonNull Instant[] asMultiplierStarts(@NonNull final List<Timestamp> times) {
        final var n = times.size();
        final var starts = new Instant[n];
        for (int i = 0; i < n; i++) {
            final var time = times.get(i);
            // ThrottleMultiplier implementations use null to represent a congestion
            // level that has not even started at the current consensus time, but we
            // use the UTC epoch to represent the same thing in state
            if (!EPOCH.equals(time)) {
                starts[i] = Instant.ofEpochSecond(time.seconds(), time.nanos());
            }
        }
        return starts;
    }
}
