// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.throttle.schemas.V0490CongestionThrottleSchema.CONGESTION_LEVEL_STARTS_STATE_ID;
import static com.hedera.node.app.throttle.schemas.V0490CongestionThrottleSchema.THROTTLE_USAGE_SNAPSHOTS_STATE_ID;
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
import com.hedera.node.app.hapi.utils.throttles.LeakyBucketDeterministicThrottle;
import com.hedera.node.app.hapi.utils.throttles.OpsDurationDeterministicThrottle;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.throttle.annotations.BackendThrottle;
import com.hedera.node.app.throttle.annotations.IngestThrottle;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.JumboTransactionsConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
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
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages the throttling service for the node.
 * This is a service orchestrator for rebuild/reset/snapshot operations.
 */
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
     * @param genesis true if this is the genesis state
     */
    public void init(@NonNull final State state, @NonNull final Bytes throttleDefinitions, final boolean genesis) {
        requireNonNull(state);
        // Apply configuration for gas, bytes and ops duration throttles
        applyGasConfig();
        applyBytesConfig();
        applyOpsDurationConfig();
        // Create backend/frontend throttles from the configured system file
        rebuildThrottlesFrom(throttleDefinitions);
        // Reset multiplier expectations
        congestionMultipliers.resetExpectations();
        // Rehydrate the internal state of the throttling service if not at genesis
        if (!genesis) {
            final var serviceStates = state.getReadableStates(CongestionThrottleService.NAME);
            resetThrottlesFromUsageSnapshots(serviceStates);
            syncFromCongestionLevelStarts(serviceStates);
        }
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
     * A snapshot of the accumulated in-memory throttle usage and congestion-level starts: the usage of every backend
     * HAPI bucket (including high-volume buckets), the gas and ops-duration throttle usage, the congestion-level
     * starts, and the usage of the ingest buckets. Captured before a dispatched system file update rebuilds any
     * throttles, so recovery can restore exactly what the update discarded if the dispatch is rolled back.
     *
     * <p>The snapshot also remembers the bucket instances it was taken from. Recovery restores a bucket's usage only
     * if the bucket was rebuilt since the capture; a bucket that is still the captured instance was left intact by the
     * update, so the usage it accumulated after the capture is kept. Likewise the congestion-level starts are restored
     * only if the congestion multipliers discarded them since the capture.
     *
     * @param hapiUsage the usage snapshots of the backend HAPI buckets, in {@code allActiveThrottlesIncludingHighVolume}
     * order
     * @param gasUsage the gas throttle usage snapshot
     * @param opsDurationUsage the ops-duration throttle usage snapshot
     * @param utilizationStarts the entity-utilization congestion-level starts, or null
     * @param gasStarts the gas-throttle-multiplier congestion-level starts, or null
     * @param buckets the backend bucket instances the usage was captured from, or null if unknown, in which case every
     * bucket is treated as rebuilt
     * @param ingestUsage the usage of the ingest buckets, or null if not captured
     */
    public record PreservedThrottleUsage(
            @NonNull List<ThrottleUsageSnapshot> hapiUsage,
            @NonNull ThrottleUsageSnapshot gasUsage,
            @NonNull ThrottleUsageSnapshot opsDurationUsage,
            @Nullable Instant[] utilizationStarts,
            @Nullable Instant[] gasStarts,
            @Nullable CapturedBuckets buckets,
            @Nullable IngestUsage ingestUsage) {

        public PreservedThrottleUsage(
                @NonNull final List<ThrottleUsageSnapshot> hapiUsage,
                @NonNull final ThrottleUsageSnapshot gasUsage,
                @NonNull final ThrottleUsageSnapshot opsDurationUsage,
                @Nullable final Instant[] utilizationStarts,
                @Nullable final Instant[] gasStarts) {
            this(hapiUsage, gasUsage, opsDurationUsage, utilizationStarts, gasStarts, null, null);
        }
    }

    /**
     * The backend bucket instances a {@link PreservedThrottleUsage} was captured from.
     *
     * @param hapi the HAPI buckets, in {@code allActiveThrottlesIncludingHighVolume} order
     * @param gas the gas throttle
     * @param opsDuration the ops-duration throttle
     * @param congestionStartsGeneration the generation of the congestion multipliers' state at the capture
     */
    public record CapturedBuckets(
            @NonNull List<DeterministicThrottle> hapi,
            @NonNull LeakyBucketDeterministicThrottle gas,
            @NonNull OpsDurationDeterministicThrottle opsDuration,
            long congestionStartsGeneration) {}

    /**
     * The usage of the ingest buckets with the bucket instances it was captured from.
     *
     * @param hapiBuckets the ingest HAPI buckets, in {@code allActiveThrottlesIncludingHighVolume} order
     * @param hapiUsage the usage snapshots of the ingest HAPI buckets, in the same order
     * @param gasBucket the ingest gas throttle
     * @param gasUsage the ingest gas throttle usage snapshot
     * @param bytesBucket the ingest bytes throttle
     * @param bytesUsage the ingest bytes throttle usage snapshot
     */
    public record IngestUsage(
            @NonNull List<DeterministicThrottle> hapiBuckets,
            @NonNull List<ThrottleUsageSnapshot> hapiUsage,
            @NonNull LeakyBucketDeterministicThrottle gasBucket,
            @NonNull ThrottleUsageSnapshot gasUsage,
            @NonNull LeakyBucketDeterministicThrottle bytesBucket,
            @NonNull ThrottleUsageSnapshot bytesUsage) {}

    /**
     * Captures the accumulated in-memory throttle usage and the congestion-level starts. Read-only; taken before a
     * dispatched system file update rebuilds the throttles, so recovery can restore the usage the update discarded if
     * the dispatch is rolled back.
     *
     * <p>The ingest buckets are charged by other threads, under the monitor of the ingest accumulator (see
     * {@link SynchronizedThrottleAccumulator}); their usage is read under the same monitor, so the snapshot is not
     * torn by a concurrent decision.
     *
     * @return the captured usage
     */
    public PreservedThrottleUsage captureThrottleUsage() {
        final var backendHapi = List.copyOf(backendThrottle.allActiveThrottlesIncludingHighVolume());
        final var backendGas = backendThrottle.gasLimitThrottle();
        final var backendOpsDuration = backendThrottle.opsDurationThrottle();
        final IngestUsage ingestUsage;
        synchronized (ingestThrottle) {
            final var ingestHapi = List.copyOf(ingestThrottle.allActiveThrottlesIncludingHighVolume());
            final var ingestGas = ingestThrottle.gasLimitThrottle();
            final var ingestBytes = ingestThrottle.bytesLimitThrottle();
            ingestUsage = new IngestUsage(
                    ingestHapi,
                    usageOf(ingestHapi),
                    ingestGas,
                    ingestGas.usageSnapshot(),
                    ingestBytes,
                    ingestBytes.usageSnapshot());
        }
        return new PreservedThrottleUsage(
                usageOf(backendHapi),
                backendGas.usageSnapshot(),
                backendOpsDuration.usageSnapshot(),
                congestionMultipliers.entityUtilizationCongestionStarts(),
                congestionMultipliers.gasThrottleMultiplierCongestionStarts(),
                new CapturedBuckets(
                        backendHapi,
                        backendGas,
                        backendOpsDuration,
                        congestionMultipliers.congestionStartsGeneration()),
                ingestUsage);
    }

    private static List<ThrottleUsageSnapshot> usageOf(@NonNull final List<DeterministicThrottle> buckets) {
        return buckets.stream().map(DeterministicThrottle::usageSnapshot).toList();
    }

    /**
     * Brings the configuration-dependent throttles back in step with the committed configuration after a rolled-back
     * network-properties update. Rebuilds them only if {@code refresh} says the settings they are built from differ
     * between the rolled-back and the committed configuration, and then restores the given captured usage into every
     * gas, bytes and ops-duration bucket that was rebuilt since the capture, along with the congestion-level starts if
     * the congestion multipliers discarded them since the capture. A bucket no rebuild touched keeps the usage it
     * accumulated after the capture, and the HAPI buckets, which a network-properties update never rebuilds, are left
     * alone.
     *
     * @param refresh whether the settings the configuration-dependent throttles are built from must be re-applied
     * @param usage the usage captured before the rolled-back update, by {@link #captureThrottleUsage()}, or null to
     * preserve the usage accumulated up to this call
     */
    public void recoverThrottleConfiguration(final boolean refresh, @Nullable final PreservedThrottleUsage usage) {
        // Hold the ingest monitor from the rebuild to the restore, so no ingest decision charges a rebuilt bucket
        // only to have that charge overwritten by the restored usage
        synchronized (ingestThrottle) {
            final var restoring = usage != null ? usage : captureThrottleUsage();
            if (refresh) {
                refreshThrottleConfiguration();
            }
            restoreConfigurationUsage(restoring);
        }
    }

    /**
     * Restores the throttles to the given committed definitions and the given captured usage. Used by recovery after a
     * rolled-back throttle-definitions update: if the rolled-back update changed the active definitions, the buckets are
     * rebuilt from the committed ones, and the usage captured before the update is restored into the HAPI buckets that
     * were rebuilt since the capture, along with the congestion-level starts if the congestion multipliers discarded
     * them since the capture. The gas, bytes and ops-duration buckets, which a throttle-definitions update never
     * rebuilds, keep the usage they accumulated after the capture.
     *
     * @param committedDefinitions the committed throttle definitions
     * @param usage the usage captured before the rolled-back update, by {@link #captureThrottleUsage()}
     * @return the success status of the committed definitions
     */
    public ResponseCodeEnum restoreThrottles(
            @NonNull final Bytes committedDefinitions, @NonNull final PreservedThrottleUsage usage) {
        // Hold the ingest monitor from the rebuild to the restore, so no ingest decision charges a rebuilt bucket
        // only to have that charge overwritten by the restored usage
        synchronized (ingestThrottle) {
            final var status = recreateThrottles(committedDefinitions);
            restoreHapiUsage(usage);
            return status;
        }
    }

    /**
     * Returns whether the configuration settings the configuration-dependent throttles are built from differ between
     * the two configurations. When they do not, a network-properties update needs no throttle refresh, and skipping
     * it keeps the accumulated gas, bytes and ops-duration usage and the congestion period intact.
     *
     * @param before the configuration before the update
     * @param after the configuration after the update
     * @return true if any setting the throttle configuration depends on changed
     */
    public static boolean throttleConfigurationChanged(
            @NonNull final Configuration before, @NonNull final Configuration after) {
        requireNonNull(before);
        requireNonNull(after);
        return !ThrottleSettings.from(before).equals(ThrottleSettings.from(after));
    }

    /**
     * The settings that {@link #refreshThrottleConfiguration()} builds the gas, bytes and ops-duration throttles from.
     * (The congestion multipliers read their own settings when they update, so they need no refresh on a change.)
     */
    private record ThrottleSettings(
            long maxGasPerSec,
            long maxGasPerSecBackend,
            boolean throttleByGas,
            long opsDurationThrottleCapacity,
            long opsDurationThrottleUnitsFreedPerSecond,
            boolean throttleByOpsDuration,
            long maxBytesPerSec,
            boolean jumboTransactionsEnabled) {
        static ThrottleSettings from(@NonNull final Configuration configuration) {
            final var contractsConfig = configuration.getConfigData(ContractsConfig.class);
            final var jumboConfig = configuration.getConfigData(JumboTransactionsConfig.class);
            return new ThrottleSettings(
                    contractsConfig.maxGasPerSec(),
                    contractsConfig.maxGasPerSecBackend(),
                    contractsConfig.throttleThrottleByGas(),
                    contractsConfig.opsDurationThrottleCapacity(),
                    contractsConfig.opsDurationThrottleUnitsFreedPerSecond(),
                    contractsConfig.throttleThrottleByOpsDuration(),
                    jumboConfig.maxBytesPerSec(),
                    jumboConfig.isEnabled());
        }
    }

    private static void resetUsageLeniently(
            @NonNull final Consumer<ThrottleUsageSnapshot> reset,
            @NonNull final ThrottleUsageSnapshot snapshot,
            @NonNull final String name) {
        try {
            reset.accept(snapshot);
        } catch (final RuntimeException e) {
            log.warn(
                    "Captured {} throttle usage {} is not compatible with the current throttle, not restoring it",
                    name,
                    snapshot,
                    e);
        }
    }

    /**
     * Restores the captured usage into the backend and ingest HAPI buckets rebuilt since the capture, and the
     * congestion-level starts if they were discarded since the capture.
     */
    private void restoreHapiUsage(@NonNull final PreservedThrottleUsage usage) {
        final var buckets = usage.buckets();
        if (buckets == null
                || !sameInstances(buckets.hapi(), backendThrottle.allActiveThrottlesIncludingHighVolume())) {
            ThrottleAccumulator.restoreThrottleUsage(
                    ThrottleAccumulator.selectedThrottlesFor(backendThrottle, usage.hapiUsage()),
                    usage.hapiUsage(),
                    ThrottleAccumulator.SnapshotMismatchPolicy.SKIP);
        }
        restoreCongestionStarts(usage);
        final var ingest = usage.ingestUsage();
        if (ingest != null
                && !sameInstances(ingest.hapiBuckets(), ingestThrottle.allActiveThrottlesIncludingHighVolume())) {
            ThrottleAccumulator.restoreThrottleUsage(
                    ThrottleAccumulator.selectedThrottlesFor(ingestThrottle, ingest.hapiUsage()),
                    ingest.hapiUsage(),
                    ThrottleAccumulator.SnapshotMismatchPolicy.SKIP);
        }
    }

    /**
     * Restores the captured usage into the gas, bytes and ops-duration buckets rebuilt since the capture, and the
     * congestion-level starts if they were discarded since the capture. Like the HAPI bucket restore, an incompatible
     * snapshot (e.g. usage above a capacity the committed configuration shrank) is skipped rather than failing the
     * recovery of everything else.
     */
    private void restoreConfigurationUsage(@NonNull final PreservedThrottleUsage usage) {
        final var buckets = usage.buckets();
        final var gasThrottle = backendThrottle.gasLimitThrottle();
        if (buckets == null || buckets.gas() != gasThrottle) {
            resetUsageLeniently(gasThrottle::resetUsageTo, usage.gasUsage(), "gas");
        }
        final var opsDurationThrottle = backendThrottle.opsDurationThrottle();
        if (buckets == null || buckets.opsDuration() != opsDurationThrottle) {
            resetUsageLeniently(opsDurationThrottle::resetUsageTo, usage.opsDurationUsage(), "ops duration");
        }
        restoreCongestionStarts(usage);
        final var ingest = usage.ingestUsage();
        if (ingest != null) {
            final var ingestGasThrottle = ingestThrottle.gasLimitThrottle();
            if (ingest.gasBucket() != ingestGasThrottle) {
                resetUsageLeniently(ingestGasThrottle::resetUsageTo, ingest.gasUsage(), "ingest gas");
            }
            final var ingestBytesThrottle = ingestThrottle.bytesLimitThrottle();
            if (ingest.bytesBucket() != ingestBytesThrottle) {
                resetUsageLeniently(ingestBytesThrottle::resetUsageTo, ingest.bytesUsage(), "ingest bytes");
            }
        }
    }

    /**
     * Restores the captured congestion-level starts if the congestion multipliers discarded them since the capture,
     * which they do whenever the throttles are rebuilt or their own configuration changes. A rolled-back update may
     * have left the multipliers on its configuration, so they are first brought in step with the current one; the
     * restored starts would otherwise be discarded again the next time the multipliers update.
     */
    private void restoreCongestionStarts(@NonNull final PreservedThrottleUsage usage) {
        final var buckets = usage.buckets();
        if (buckets != null
                && buckets.congestionStartsGeneration() == congestionMultipliers.congestionStartsGeneration()) {
            return;
        }
        congestionMultipliers.resetExpectations();
        final var utilizationStarts = usage.utilizationStarts();
        if (utilizationStarts != null && utilizationStarts.length > 0) {
            congestionMultipliers.resetUtilizationScaledThrottleMultiplierStarts(utilizationStarts);
        }
        final var gasStarts = usage.gasStarts();
        if (gasStarts != null && gasStarts.length > 0) {
            congestionMultipliers.resetGasThrottleMultiplierStarts(gasStarts);
        }
    }

    private static boolean sameInstances(
            @NonNull final List<DeterministicThrottle> captured, @NonNull final List<DeterministicThrottle> current) {
        if (captured.size() != current.size()) {
            return false;
        }
        for (int i = 0, n = captured.size(); i < n; i++) {
            if (captured.get(i) != current.get(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Recreates the throttles based on the given throttle definitions.
     *
     * @param encoded the serialized throttle definitions
     * @return the success status to use if the update was via HAPI
     */
    public ResponseCodeEnum recreateThrottles(@NonNull final Bytes encoded) {
        final var validatedThrottles = throttleParser.parse(encoded);
        // Only rebuild the buckets and reset the congestion multipliers when the definitions actually changed. A
        // rejected or rolled-back upload re-derives from the unchanged committed definitions; rebuilding then would
        // needlessly discard the accumulated bucket usage and restart the minimum congestion period.
        if (!validatedThrottles.throttleDefinitions().equals(activeDefinitions)) {
            ingestThrottle.rebuildFor(validatedThrottles.throttleDefinitions());
            backendThrottle.rebuildFor(validatedThrottles.throttleDefinitions());
            this.activeDefinitions = validatedThrottles.throttleDefinitions();
            congestionMultipliers.resetExpectations();
        }
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
        final var hapiThrottles = backendThrottle.allActiveThrottlesIncludingHighVolume();
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
                serviceStates.getSingleton(THROTTLE_USAGE_SNAPSHOTS_STATE_ID);
        throttleSnapshots.put(
                new ThrottleUsageSnapshots(hapiThrottleSnapshots, gasThrottleSnapshot, opsDurationThrottleSnapshot));
    }

    private void saveCongestionLevelStartsTo(@NonNull final WritableStates serviceStates) {
        final WritableSingletonState<CongestionLevelStarts> congestionLevelStarts =
                serviceStates.getSingleton(CONGESTION_LEVEL_STARTS_STATE_ID);
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
                CongestionStarts.from(serviceStates.getSingleton(CONGESTION_LEVEL_STARTS_STATE_ID));
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
                serviceStates.getSingleton(THROTTLE_USAGE_SNAPSHOTS_STATE_ID);
        final var usageSnapshots = requireNonNull(usageSnapshotsState.get());
        ThrottleAccumulator.restoreThrottleUsage(
                ThrottleAccumulator.selectedThrottlesFor(backendThrottle, usageSnapshots.tpsThrottles()),
                usageSnapshots.tpsThrottles(),
                ThrottleAccumulator.SnapshotMismatchPolicy.SKIP);
        if (usageSnapshots.hasGasThrottle()) {
            backendThrottle.gasLimitThrottle().resetUsageTo(usageSnapshots.gasThrottleOrThrow());
        }
        if (usageSnapshots.hasEvmOpsDurationThrottle()) {
            backendThrottle.opsDurationThrottle().resetUsageTo(usageSnapshots.evmOpsDurationThrottleOrThrow());
        }
    }

    public void resetThrottlesUnconditionally(@NonNull final ReadableStates serviceStates) {
        final ReadableSingletonState<ThrottleUsageSnapshots> usageSnapshotsState =
                serviceStates.getSingleton(THROTTLE_USAGE_SNAPSHOTS_STATE_ID);
        final var usageSnapshots = requireNonNull(usageSnapshotsState.get());
        resetUnconditionally(
                ThrottleAccumulator.selectedThrottlesFor(backendThrottle, usageSnapshots.tpsThrottles()),
                usageSnapshots.tpsThrottles());
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
     * @param hederaFunctionality the functionality whose bucket the capacity was claimed against
     * @param useHighVolumeBucket whether the capacity was claimed against the high-volume bucket, so it is
     * leaked back into the same bucket it was charged to
     */
    public void reclaimFrontendThrottleCapacity(
            final int numCapacity, final HederaFunctionality hederaFunctionality, final boolean useHighVolumeBucket) {
        try {
            ingestThrottle.leakCapacityForNOfUnscaled(numCapacity, hederaFunctionality, useHighVolumeBucket);
        } catch (Exception ignore) {
            // Ignore if the frontend bucket has already leaked all the capacity
            // used for throttling the transaction on the frontend
        }
    }

    /**
     * Returns whether the implicit-creation capacity for the given transaction was claimed against the
     * high-volume frontend bucket, so the reclaim can leak it back into the same bucket it was charged to.
     *
     * @param body the transaction body
     * @param function the functionality of the transaction
     * @param implicitCreationsCount the number of implicit creations
     * @return whether the high-volume bucket was used
     */
    public boolean usesHighVolumeBucketForImplicitCreations(
            @NonNull final TransactionBody body,
            @NonNull final HederaFunctionality function,
            final int implicitCreationsCount) {
        return ingestThrottle.usesHighVolumeBucketForImplicitCreations(
                function, body.highVolume(), implicitCreationsCount);
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
