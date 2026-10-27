// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.schemas;

import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.assertArg;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.impl.schemas.V0800ClprSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V0800ClprSchemaTest {
    @Mock
    private MigrationContext<SemanticVersion> context;

    @Mock
    private WritableStates states;

    @Mock
    private WritableSingletonState<ClprLedgerConfiguration> configState;

    private final V0800ClprSchema subject = new V0800ClprSchema();

    @Test
    void genesisUsesNormalInitialization() {
        subject.restart(context);

        verify(context, never()).newStates();
    }

    @ParameterizedTest
    @CsvSource({"80,0,alpha.1", "80,1,''", "81,0,''"})
    void preservesAdminChangesOnLaterSoftware(final int minor, final int patch, final String pre) {
        given(context.previousVersion())
                .willReturn(SemanticVersion.newBuilder()
                        .minor(minor)
                        .patch(patch)
                        .pre(pre)
                        .build());

        subject.restart(context);

        verify(context, never()).newStates();
    }

    @Test
    void tightensOldDefaultsAndPreservesOtherConfiguration() {
        final var existing = legacyConfig(new Timestamp(123, 456));
        givenLegacyState(existing);

        subject.restart(context);

        verify(configState).put(assertArg(updated -> {
            assertThat(updated)
                    .isEqualTo(existing.copyBuilder()
                            .timestamp(new Timestamp(123, 457))
                            .throttles(existing.throttlesOrThrow()
                                    .copyBuilder()
                                    .maxMessagesPerBundle(100)
                                    .maxQueueDepth(1_000)
                                    .maxMessagePayloadBytes(4_096)
                                    .maxPeerEndpoints(10)
                                    .build())
                            .build());
        }));
    }

    @Test
    void preservesTighterCustomLimitsWithoutChangingTimestamp() {
        final var existing = legacyConfig(new Timestamp(123, 456))
                .copyBuilder()
                .throttles(ClprThrottles.newBuilder()
                        .maxMessagesPerBundle(20)
                        .maxQueueDepth(30)
                        .maxMessagePayloadBytes(100)
                        .maxPeerEndpoints(2)
                        .maxGasPerMessage(200_000)
                        .maxSyncBytes(2_000_000)
                        .build())
                .build();
        givenLegacyState(existing);

        subject.restart(context);

        verify(configState, never()).put(any());
    }

    @Test
    void rerunningTheMigrationDoesNotAdvanceTheTimestampAgain() {
        givenLegacyState(legacyConfig(new Timestamp(123, 456)));
        subject.restart(context);
        final var captor = ArgumentCaptor.forClass(ClprLedgerConfiguration.class);
        verify(configState).put(captor.capture());
        given(configState.get()).willReturn(captor.getValue());

        subject.restart(context);

        verify(configState, times(1)).put(any());
    }

    @Test
    void advancesTimestampAcrossSecondBoundary() {
        givenLegacyState(legacyConfig(new Timestamp(123, 999_999_999)));

        subject.restart(context);

        verify(configState)
                .put(assertArg(updated -> assertThat(updated.timestamp()).isEqualTo(new Timestamp(124, 0))));
    }

    @Test
    void leavesMissingConfigurationForPostUpgradeInitialization() {
        givenLegacyState(null);

        subject.restart(context);

        verify(configState, never()).put(any());
    }

    private void givenLegacyState(final ClprLedgerConfiguration configuration) {
        given(context.previousVersion())
                .willReturn(SemanticVersion.newBuilder().minor(79).build());
        given(context.newStates()).willReturn(states);
        given(states.<ClprLedgerConfiguration>getSingleton(LEDGER_CONFIGURATION_STATE_ID))
                .willReturn(configState);
        given(configState.get()).willReturn(configuration);
    }

    private static ClprLedgerConfiguration legacyConfig(final Timestamp timestamp) {
        return ClprLedgerConfiguration.newBuilder()
                .chainId("hiero:test")
                .protocolVersion(1)
                .serviceAddress(Bytes.fromHex("016e"))
                .initialTrustAnchor(Bytes.wrap("anchor"))
                .initialTrustAnchorId(Bytes.wrap("anchor-id"))
                .timestamp(timestamp)
                .throttles(ClprThrottles.newBuilder()
                        .maxMessagesPerBundle(1_000)
                        .maxQueueDepth(10_000)
                        .maxMessagePayloadBytes(65_536)
                        .maxPeerEndpoints(0)
                        .maxGasPerMessage(15_000_000)
                        .maxSyncBytes(4_194_304)
                        .maxLocalEndpoints(5)
                        .build())
                .build();
    }
}
