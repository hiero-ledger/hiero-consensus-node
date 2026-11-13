// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.throttle.schemas.V0490CongestionThrottleSchema.CONGESTION_LEVEL_STARTS_STATE_ID;
import static com.hedera.node.app.throttle.schemas.V0490CongestionThrottleSchema.THROTTLE_USAGE_SNAPSHOTS_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.congestion.CongestionLevelStarts;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle;
import com.hedera.node.app.hapi.utils.throttles.LeakyBucketDeterministicThrottle;
import com.hedera.node.app.hapi.utils.throttles.OpsDurationDeterministicThrottle;
import com.hedera.node.app.throttle.schemas.V0490CongestionThrottleSchema;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ThrottleServiceManagerTest {

    private static final Bytes MOCK_ENCODED_THROTTLE_DEFS = Bytes.wrap("NOPE");
    private static final ThrottleDefinitions MOCK_THROTTLE_DEFS = ThrottleDefinitions.DEFAULT;
    private static final ThrottleUsageSnapshots MOCK_THROTTLE_USAGE_SNAPSHOTS = new ThrottleUsageSnapshots(
            List.of(new ThrottleUsageSnapshot(123L, EPOCH)),
            new ThrottleUsageSnapshot(456L, EPOCH),
            new ThrottleUsageSnapshot(789L, EPOCH));
    private static final CongestionLevelStarts MOCK_CONGESTION_LEVEL_STARTS =
            new CongestionLevelStarts(List.of(new Timestamp(1L, 2), EPOCH), List.of(new Timestamp(3L, 4), EPOCH));
    private static final ThrottleUsageSnapshot MOCK_USAGE_SNAPSHOT =
            new ThrottleUsageSnapshot(123L, new Timestamp(1_234_567L, 890));

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private ThrottleParser throttleParser;

    @Mock
    private ThrottleAccumulator ingestThrottle;

    @Mock
    private ThrottleAccumulator backendThrottle;

    @Mock
    private CongestionMultipliers congestionMultipliers;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<ThrottleUsageSnapshots> writableThrottleSnapshots;

    @Mock
    private WritableSingletonState<CongestionLevelStarts> writableLevelStarts;

    @Mock
    private ReadableStates throttleReadableStates;

    @Mock
    private State state;

    @Mock
    private ReadableSingletonState<ThrottleUsageSnapshots> throttleUsageSnapshots;

    @Mock
    private ReadableSingletonState<CongestionLevelStarts> congestionLevelStarts;

    @Mock
    private LeakyBucketDeterministicThrottle gasThrottle;

    @Mock
    private LeakyBucketDeterministicThrottle bytesThrottle;

    @Mock
    private OpsDurationDeterministicThrottle opsDurationThrottle;

    @Mock
    private DeterministicThrottle cryptoTransferThrottle;

    private ThrottleServiceManager subject;

    @BeforeEach
    void setUp() {
        subject = new ThrottleServiceManager(throttleParser, ingestThrottle, backendThrottle, congestionMultipliers);
    }

    @Test
    void initsAsExpected() {
        givenMockThrottleDefs();
        givenReadableThrottleState();
        givenThrottleMocks();

        final var inOrder = inOrder(
                configProvider,
                ingestThrottle,
                backendThrottle,
                congestionMultipliers,
                cryptoTransferThrottle,
                gasThrottle,
                bytesThrottle);

        subject.init(state, MOCK_ENCODED_THROTTLE_DEFS, false);

        inOrder.verify(ingestThrottle).applyGasConfig();
        inOrder.verify(backendThrottle).applyGasConfig();
        inOrder.verify(ingestThrottle).applyBytesConfig();
        inOrder.verify(ingestThrottle).rebuildFor(MOCK_THROTTLE_DEFS);
        inOrder.verify(backendThrottle).rebuildFor(MOCK_THROTTLE_DEFS);
        inOrder.verify(congestionMultipliers).resetExpectations();
        inOrder.verify(cryptoTransferThrottle)
                .resetUsageTo(MOCK_THROTTLE_USAGE_SNAPSHOTS.tpsThrottles().getFirst());
        inOrder.verify(gasThrottle).resetUsageTo(MOCK_THROTTLE_USAGE_SNAPSHOTS.gasThrottleOrThrow());
        inOrder.verify(congestionMultipliers)
                .resetUtilizationScaledThrottleMultiplierStarts(asNullTerminatedInstants(
                        MOCK_CONGESTION_LEVEL_STARTS.genericLevelStarts().getFirst()));
        inOrder.verify(congestionMultipliers)
                .resetGasThrottleMultiplierStarts(asNullTerminatedInstants(
                        MOCK_CONGESTION_LEVEL_STARTS.gasLevelStarts().getFirst()));
    }

    @Test
    void savesInternalStateAsExpected() {
        givenWritableThrottleState();
        givenThrottleMocks();
        given(gasThrottle.usageSnapshot()).willReturn(MOCK_USAGE_SNAPSHOT);
        given(backendThrottle.opsDurationThrottle()).willReturn(opsDurationThrottle);
        given(opsDurationThrottle.usageSnapshot()).willReturn(MOCK_USAGE_SNAPSHOT);
        given(congestionMultipliers.entityUtilizationCongestionStarts())
                .willReturn(asNullTerminatedInstants(
                        MOCK_CONGESTION_LEVEL_STARTS.genericLevelStarts().getFirst()));
        given(congestionMultipliers.gasThrottleMultiplierCongestionStarts())
                .willReturn(asNullTerminatedInstants(
                        MOCK_CONGESTION_LEVEL_STARTS.gasLevelStarts().getFirst()));

        subject.saveThrottleSnapshotsAndCongestionLevelStartsTo(state);

        verify(writableThrottleSnapshots)
                .put(new ThrottleUsageSnapshots(
                        List.of(MOCK_USAGE_SNAPSHOT), MOCK_USAGE_SNAPSHOT, MOCK_USAGE_SNAPSHOT));
        verify(writableLevelStarts).put(MOCK_CONGESTION_LEVEL_STARTS);
    }

    @Test
    void refreshesThrottleConfigurationAsExpected() {
        final var inOrder = inOrder(ingestThrottle, backendThrottle, congestionMultipliers);
        subject.refreshThrottleConfiguration();
        inOrder.verify(ingestThrottle).applyGasConfig();
        inOrder.verify(backendThrottle).applyGasConfig();
        inOrder.verify(congestionMultipliers).resetExpectations();
    }

    @Test
    void recreatesThrottlesAsExpected() {
        final var inOrder = inOrder(ingestThrottle, backendThrottle, congestionMultipliers);
        given(throttleParser.parse(MOCK_ENCODED_THROTTLE_DEFS))
                .willReturn(new ThrottleParser.ValidatedThrottles(MOCK_THROTTLE_DEFS, SUCCESS));
        subject.recreateThrottles(MOCK_ENCODED_THROTTLE_DEFS);
        inOrder.verify(ingestThrottle).rebuildFor(MOCK_THROTTLE_DEFS);
        inOrder.verify(backendThrottle).rebuildFor(MOCK_THROTTLE_DEFS);
        inOrder.verify(congestionMultipliers).resetExpectations();
    }

    @Test
    void updateAllMetricsAsExpected() {
        subject.updateAllMetrics();
        verify(ingestThrottle).updateAllMetrics();
        verify(backendThrottle).updateAllMetrics();
    }

    private Instant[] asNullTerminatedInstants(Timestamp timestamp) {
        return new Instant[] {Instant.ofEpochSecond(timestamp.seconds(), timestamp.nanos()), null};
    }

    private void givenThrottleMocks() {
        given(backendThrottle.allActiveThrottlesIncludingHighVolume()).willReturn(List.of(cryptoTransferThrottle));
        given(cryptoTransferThrottle.usageSnapshot()).willReturn(MOCK_USAGE_SNAPSHOT);
        given(backendThrottle.gasLimitThrottle()).willReturn(gasThrottle);
        given(backendThrottle.opsDurationThrottle()).willReturn(opsDurationThrottle);
    }

    private void givenMockThrottleDefs() {
        given(throttleParser.parse(MOCK_ENCODED_THROTTLE_DEFS))
                .willReturn(new ThrottleParser.ValidatedThrottles(MOCK_THROTTLE_DEFS, SUCCESS));
    }

    private void givenReadableThrottleState() {
        given(state.getReadableStates(CongestionThrottleService.NAME)).willReturn(throttleReadableStates);
        given(throttleUsageSnapshots.get()).willReturn(MOCK_THROTTLE_USAGE_SNAPSHOTS);
        given(throttleReadableStates.<ThrottleUsageSnapshots>getSingleton(THROTTLE_USAGE_SNAPSHOTS_STATE_ID))
                .willReturn(throttleUsageSnapshots);
        given(congestionLevelStarts.get()).willReturn(MOCK_CONGESTION_LEVEL_STARTS);
        given(throttleReadableStates.<CongestionLevelStarts>getSingleton(
                        V0490CongestionThrottleSchema.CONGESTION_LEVEL_STARTS_STATE_ID))
                .willReturn(congestionLevelStarts);
    }

    private void givenWritableThrottleState() {
        given(state.getWritableStates(CongestionThrottleService.NAME)).willReturn(writableStates);
        given(writableStates.<ThrottleUsageSnapshots>getSingleton(THROTTLE_USAGE_SNAPSHOTS_STATE_ID))
                .willReturn(writableThrottleSnapshots);
        given(writableStates.<CongestionLevelStarts>getSingleton(CONGESTION_LEVEL_STARTS_STATE_ID))
                .willReturn(writableLevelStarts);
    }

    @Test
    void throttleConfigurationChangedTracksOnlyTheSettingsTheThrottlesAreBuiltFrom() {
        final var base = HederaTestConfigBuilder.create().getOrCreateConfig();
        final var sameThrottleSettings = HederaTestConfigBuilder.create()
                .withValue("tokens.maxPerAccount", 12345)
                .getOrCreateConfig();
        final var differentGas = HederaTestConfigBuilder.create()
                .withValue("contracts.maxGasPerSecBackend", 123_456_789L)
                .getOrCreateConfig();
        final var differentBytes = HederaTestConfigBuilder.create()
                .withValue("jumboTransactions.maxBytesPerSec", 1L)
                .getOrCreateConfig();

        assertThat(ThrottleServiceManager.throttleConfigurationChanged(base, base))
                .isFalse();
        assertThat(ThrottleServiceManager.throttleConfigurationChanged(base, sameThrottleSettings))
                .isFalse();
        assertThat(ThrottleServiceManager.throttleConfigurationChanged(base, differentGas))
                .isTrue();
        assertThat(ThrottleServiceManager.throttleConfigurationChanged(base, differentBytes))
                .isTrue();
    }

    @Test
    void recoveryRestoresCongestionStartsTheMultipliersDiscardedEvenIfNoBucketWasRebuilt() {
        final var utilizationStarts = new Instant[] {Instant.ofEpochSecond(1L), null};
        final var gasStarts = new Instant[] {Instant.ofEpochSecond(2L), null};
        givenCapturableThrottles();
        given(congestionMultipliers.entityUtilizationCongestionStarts()).willReturn(utilizationStarts);
        given(congestionMultipliers.gasThrottleMultiplierCongestionStarts()).willReturn(gasStarts);
        given(congestionMultipliers.congestionStartsGeneration()).willReturn(5L);
        final var usage = subject.captureThrottleUsage();

        // A rolled-back update changed the multipliers' own configuration, so they discarded their starts while
        // every bucket stayed as it was
        given(congestionMultipliers.congestionStartsGeneration()).willReturn(6L);
        subject.recoverThrottleConfiguration(false, usage);

        final var inOrder = inOrder(congestionMultipliers);
        inOrder.verify(congestionMultipliers).resetExpectations();
        inOrder.verify(congestionMultipliers).resetUtilizationScaledThrottleMultiplierStarts(utilizationStarts);
        inOrder.verify(congestionMultipliers).resetGasThrottleMultiplierStarts(gasStarts);
        // The buckets were not rebuilt, so their usage is left alone
        verify(gasThrottle, never()).resetUsageTo(any());
        verify(opsDurationThrottle, never()).resetUsageTo(any());
        verify(bytesThrottle, never()).resetUsageTo(any());
    }

    @Test
    void recoveryLeavesCongestionStartsAloneIfTheMultipliersKeptThem() {
        givenCapturableThrottles();
        given(congestionMultipliers.entityUtilizationCongestionStarts())
                .willReturn(new Instant[] {Instant.ofEpochSecond(1L), null});
        given(congestionMultipliers.gasThrottleMultiplierCongestionStarts())
                .willReturn(new Instant[] {Instant.ofEpochSecond(2L), null});
        given(congestionMultipliers.congestionStartsGeneration()).willReturn(5L);
        final var usage = subject.captureThrottleUsage();

        subject.recoverThrottleConfiguration(false, usage);

        verify(congestionMultipliers, never()).resetExpectations();
        verify(congestionMultipliers, never()).resetUtilizationScaledThrottleMultiplierStarts(any());
        verify(congestionMultipliers, never()).resetGasThrottleMultiplierStarts(any());
    }

    private void givenCapturableThrottles() {
        givenThrottleMocks();
        given(gasThrottle.usageSnapshot()).willReturn(MOCK_USAGE_SNAPSHOT);
        given(opsDurationThrottle.usageSnapshot()).willReturn(MOCK_USAGE_SNAPSHOT);
        given(ingestThrottle.allActiveThrottlesIncludingHighVolume()).willReturn(List.of());
        given(ingestThrottle.gasLimitThrottle()).willReturn(gasThrottle);
        given(ingestThrottle.bytesLimitThrottle()).willReturn(bytesThrottle);
        given(bytesThrottle.usageSnapshot()).willReturn(MOCK_USAGE_SNAPSHOT);
    }

    @Test
    void captureReadsTheIngestUsageHoldingTheIngestMonitor() {
        final var held = new ArrayList<Boolean>();
        givenThrottleMocks();
        given(gasThrottle.usageSnapshot()).willReturn(MOCK_USAGE_SNAPSHOT);
        given(opsDurationThrottle.usageSnapshot()).willReturn(MOCK_USAGE_SNAPSHOT);
        final var ingestGasThrottle = mock(LeakyBucketDeterministicThrottle.class);
        final var ingestHapiThrottle = mock(DeterministicThrottle.class);
        given(ingestThrottle.allActiveThrottlesIncludingHighVolume()).willReturn(List.of(ingestHapiThrottle));
        given(ingestThrottle.gasLimitThrottle()).willReturn(ingestGasThrottle);
        given(ingestThrottle.bytesLimitThrottle()).willReturn(bytesThrottle);
        given(ingestHapiThrottle.usageSnapshot()).willAnswer(invocation -> {
            held.add(Thread.holdsLock(ingestThrottle));
            return MOCK_USAGE_SNAPSHOT;
        });
        given(ingestGasThrottle.usageSnapshot()).willAnswer(invocation -> {
            held.add(Thread.holdsLock(ingestThrottle));
            return MOCK_USAGE_SNAPSHOT;
        });
        given(bytesThrottle.usageSnapshot()).willAnswer(invocation -> {
            held.add(Thread.holdsLock(ingestThrottle));
            return MOCK_USAGE_SNAPSHOT;
        });

        subject.captureThrottleUsage();

        assertThat(held).containsExactly(true, true, true);
    }

    @Test
    void configurationRecoveryRebuildsAndRestoresTheIngestBucketsInOneHoldOfTheIngestMonitor() {
        final var events = new ArrayList<String>();
        givenCapturableThrottles();
        final var usage = subject.captureThrottleUsage();
        // The refresh replaces the ingest gas and bytes buckets
        final var rebuiltIngestGas = mock(LeakyBucketDeterministicThrottle.class);
        final var rebuiltIngestBytes = mock(LeakyBucketDeterministicThrottle.class);
        given(ingestThrottle.gasLimitThrottle()).willReturn(rebuiltIngestGas);
        given(ingestThrottle.bytesLimitThrottle()).willReturn(rebuiltIngestBytes);
        willAnswer(invocation -> {
                    events.add("rebuild gas, monitor held: " + Thread.holdsLock(ingestThrottle));
                    return null;
                })
                .given(ingestThrottle)
                .applyGasConfig();
        willAnswer(invocation -> {
                    events.add("rebuild bytes, monitor held: " + Thread.holdsLock(ingestThrottle));
                    return null;
                })
                .given(ingestThrottle)
                .applyBytesConfig();
        willAnswer(invocation -> {
                    events.add("restore gas, monitor held: " + Thread.holdsLock(ingestThrottle));
                    return null;
                })
                .given(rebuiltIngestGas)
                .resetUsageTo(MOCK_USAGE_SNAPSHOT);
        willAnswer(invocation -> {
                    events.add("restore bytes, monitor held: " + Thread.holdsLock(ingestThrottle));
                    return null;
                })
                .given(rebuiltIngestBytes)
                .resetUsageTo(MOCK_USAGE_SNAPSHOT);

        subject.recoverThrottleConfiguration(true, usage);

        assertThat(events)
                .containsExactly(
                        "rebuild gas, monitor held: true",
                        "rebuild bytes, monitor held: true",
                        "restore gas, monitor held: true",
                        "restore bytes, monitor held: true");
        // The monitor is released once the recovery is done
        assertFalse(Thread.holdsLock(ingestThrottle));
    }

    @Test
    void definitionsRecoveryRebuildsAndRestoresTheIngestBucketsInOneHoldOfTheIngestMonitor() {
        final var events = new ArrayList<String>();
        givenMockThrottleDefs();
        givenCapturableThrottles();
        given(ingestThrottle.allActiveThrottlesIncludingHighVolume()).willReturn(List.of(cryptoTransferThrottle));
        given(cryptoTransferThrottle.usageSnapshot()).willReturn(MOCK_USAGE_SNAPSHOT);
        final var usage = subject.captureThrottleUsage();
        // The rebuild replaces the ingest HAPI bucket
        final var rebuiltIngestBucket = mock(DeterministicThrottle.class);
        given(ingestThrottle.allActiveThrottlesIncludingHighVolume()).willReturn(List.of(rebuiltIngestBucket));
        willAnswer(invocation -> {
                    events.add("rebuild, monitor held: " + Thread.holdsLock(ingestThrottle));
                    return null;
                })
                .given(ingestThrottle)
                .rebuildFor(MOCK_THROTTLE_DEFS);
        willAnswer(invocation -> {
                    events.add("restore, monitor held: " + Thread.holdsLock(ingestThrottle));
                    return null;
                })
                .given(rebuiltIngestBucket)
                .resetUsageTo(MOCK_USAGE_SNAPSHOT);

        subject.restoreThrottles(MOCK_ENCODED_THROTTLE_DEFS, usage);

        assertThat(events).containsExactly("rebuild, monitor held: true", "restore, monitor held: true");
        assertFalse(Thread.holdsLock(ingestThrottle));
    }
}
