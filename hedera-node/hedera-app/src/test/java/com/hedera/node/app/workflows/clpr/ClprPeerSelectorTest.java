// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.node.config.testfixtures.ClprConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprPeerSelectorTest {

    private static final Bytes CHANNEL_ID =
            Bytes.fromHex("01000000000000000000000000000000000000000000000000000000000000ab");
    private static final int GRPC_PORT = 50211;
    private static final int PEER_PORT = 50211;
    /**
     * Failures that open a breaker under the default {@code clpr.retryMaxAttempts}.
     */
    private static final int FAILURES_TO_OPEN = 5;

    private static final ClprEndpoint PEER_1 = endpoint("10.0.0.1");
    private static final ClprEndpoint PEER_2 = endpoint("10.0.0.2");
    private static final ClprEndpoint PEER_3 = endpoint("10.0.0.3");
    private static final String PEER_1_ID = "10.0.0.1:" + PEER_PORT;
    private static final String PEER_2_ID = "10.0.0.2:" + PEER_PORT;
    private static final String PEER_3_ID = "10.0.0.3:" + PEER_PORT;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration versionedConfig;

    @Mock
    private NetworkInfo networkInfo;

    private ClprPeerSelector subject;

    @BeforeEach
    void setUp() {
        lenient().when(configProvider.getConfiguration()).thenReturn(versionedConfig);
        lenient()
                .when(versionedConfig.getConfigData(ClprConfig.class))
                .thenReturn(ClprConfigBuilder.newBuilder()
                        .enabled(true)
                        .syncPeerExclusionEnabled(true)
                        .build());
        // Self-detection treats a loopback host on the configured gRPC port as this node.
        lenient()
                .when(versionedConfig.getConfigData(GrpcConfig.class))
                .thenReturn(
                        new GrpcConfig(GRPC_PORT, 50212, true, 50213, 60211, 60212, 4_194_304, 4_194_304, 4_194_304));
        subject = new ClprPeerSelector(configProvider, networkInfo);
    }

    @Nested
    @DisplayName("internal getter caching")
    class GetterCachingTests {

        @Test
        @DisplayName("given the same peer twice, then getCircuitBreaker() returns the cached instance")
        void givenSamePeer_thenGetCircuitBreakerReturnsCachedInstance() {
            final var cb1 = subject.getCircuitBreaker(PEER_1_ID);
            final var cb2 = subject.getCircuitBreaker(PEER_1_ID);
            assertThat(cb1).isSameAs(cb2);
        }

        @Test
        @DisplayName("given two different peers, then getCircuitBreaker() returns distinct instances")
        void givenDifferentPeers_thenGetCircuitBreakerReturnsDistinctInstances() {
            final var cb1 = subject.getCircuitBreaker(PEER_1_ID);
            final var cb2 = subject.getCircuitBreaker(PEER_2_ID);
            assertThat(cb1).isNotSameAs(cb2);
        }

        @Test
        @DisplayName("given the same peer twice, then getReputation() returns the cached instance")
        void givenSamePeer_thenGetReputationReturnsCachedInstance() {
            final var rep1 = subject.getReputation(PEER_1_ID);
            final var rep2 = subject.getReputation(PEER_1_ID);
            assertThat(rep1).isSameAs(rep2);
        }

        @Test
        @DisplayName("given two different peers, then getReputation() returns distinct instances")
        void givenDifferentPeers_thenGetReputationReturnsDistinctInstances() {
            final var rep1 = subject.getReputation(PEER_1_ID);
            final var rep2 = subject.getReputation(PEER_2_ID);
            assertThat(rep1).isNotSameAs(rep2);
        }
    }

    @Nested
    @DisplayName("selectEndpoint()")
    class SelectEndpoint {

        @Test
        @DisplayName("given no endpoints, then no peer is selected")
        void givenNoEndpoints_thenNoPeerIsSelected() {
            assertThat(subject.selectEndpoint(channel(), List.of())).isNull();
        }

        @Test
        @DisplayName("given a single endpoint, then it is selected under its host:port id")
        void givenSingleEndpoint_thenItIsSelected() {
            final var selected = subject.selectEndpoint(channel(), List.of(PEER_1));

            assertThat(selected).isNotNull();
            assertThat(selected.peerId()).isEqualTo(PEER_1_ID);
            assertThat(selected.endpoint()).isEqualTo(PEER_1);
        }

        @Test
        @DisplayName("given an endpoint without a service endpoint, then it is skipped")
        void givenEndpointWithoutServiceEndpoint_thenItIsSkipped() {
            final var malformed = PEER_1.copyBuilder()
                    .serviceEndpoint((ClprServiceEndpoint) null)
                    .build();

            assertThat(requireNonNull(subject.selectEndpoint(channel(), List.of(malformed, PEER_2)))
                            .peerId())
                    .isEqualTo(PEER_2_ID);
            assertThat(subject.selectEndpoint(channel(), List.of(malformed))).isNull();
        }

        @Test
        @DisplayName("given this node's own endpoint, then it is skipped")
        void givenSelfEndpoint_thenItIsSkipped() {
            final var self = endpoint("127.0.0.1")
                    .copyBuilder()
                    .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                            .ipAddress("127.0.0.1")
                            .port(GRPC_PORT)
                            .build())
                    .build();

            assertThat(subject.selectEndpoint(channel(), List.of(self, PEER_2)).peerId())
                    .isEqualTo(PEER_2_ID);
            assertThat(subject.selectEndpoint(channel(), List.of(self))).isNull();
        }

        @Test
        @DisplayName("given a peer with an open circuit breaker, then it is skipped")
        void givenOpenCircuitBreaker_thenPeerIsSkipped() {
            openBreaker(PEER_1_ID);

            assertThat(subject.getCircuitBreaker(PEER_1_ID).state()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(subject.selectEndpoint(channel(), List.of(PEER_1, PEER_2))
                            .peerId())
                    .isEqualTo(PEER_2_ID);
        }

        @Test
        @DisplayName("given every peer's circuit breaker is open, then no peer is selected")
        void givenAllCircuitBreakersOpen_thenNoPeerIsSelected() {
            openBreaker(PEER_1_ID);
            openBreaker(PEER_2_ID);

            assertThat(subject.selectEndpoint(channel(), List.of(PEER_1, PEER_2)))
                    .isNull();
        }

        @Test
        @DisplayName("given peer exclusion is disabled, then a peer with an open breaker is still selected")
        void givenPeerExclusionDisabled_thenOpenBreakerPeerIsSelected() {
            given(versionedConfig.getConfigData(ClprConfig.class))
                    .willReturn(ClprConfigBuilder.newBuilder()
                            .enabled(true)
                            .syncPeerExclusionEnabled(false)
                            .build());
            openBreaker(PEER_1_ID);

            assertThat(subject.selectEndpoint(channel(), List.of(PEER_1)).peerId())
                    .isEqualTo(PEER_1_ID);
        }

        @Test
        @DisplayName("given several healthy peers, then one of them is always selected")
        void givenSeveralHealthyPeers_thenOneOfThemIsSelected() {
            final var endpoints = List.of(PEER_1, PEER_2, PEER_3);
            for (int i = 0; i < 20; i++) {
                assertThat(subject.selectEndpoint(channel(), endpoints).peerId())
                        .isIn(PEER_1_ID, PEER_2_ID, PEER_3_ID);
            }
        }

        @Test
        @DisplayName("given peers of different reputations, then higher-reputation peers are selected more often")
        void givenDifferentReputations_thenHigherReputationIsSelectedMoreOften() {
            // Reputation weights — kept below retryMaxAttempts (5) so no circuit breaker opens:
            //   PEER_1: 0 failures → raw score 1.0
            //   PEER_2: 1 failure  → raw score 0.7
            //   PEER_3: 3 failures → raw score 0.1 (clamped at MIN_SCORE)
            // Expected proportions over the totalWeight of 1.8: ≈55.6%, ≈38.9%, ≈5.6%.
            // Default reputationDecaySeconds=300, so decay during the test is negligible.
            subject.getReputation(PEER_2_ID).recordFailure();
            final var lowRep = subject.getReputation(PEER_3_ID);
            lowRep.recordFailure();
            lowRep.recordFailure();
            lowRep.recordFailure();

            assertThat(subject.getReputation(PEER_1_ID).rawScore()).isCloseTo(1.0, offset(0.0001));
            assertThat(subject.getReputation(PEER_2_ID).rawScore()).isCloseTo(0.7, offset(0.0001));
            assertThat(lowRep.rawScore()).isCloseTo(0.1, offset(0.0001));

            final var endpoints = List.of(PEER_1, PEER_2, PEER_3);
            final int trials = 1_000;
            int highCount = 0;
            int midCount = 0;
            int lowCount = 0;
            for (int i = 0; i < trials; i++) {
                final var selected =
                        subject.selectEndpoint(channel(), endpoints).peerId();
                switch (selected) {
                    case PEER_1_ID -> highCount++;
                    case PEER_2_ID -> midCount++;
                    case PEER_3_ID -> lowCount++;
                    default -> throw new AssertionError("unexpected peer: " + selected);
                }
            }

            // Strict ordering must hold. The expected gaps are wide enough to dwarf the binomial standard
            // deviation at n=1000, so flake risk is minimal.
            assertThat(highCount)
                    .as("high-reputation peer should win more often than mid")
                    .isGreaterThan(midCount);
            assertThat(midCount)
                    .as("mid-reputation peer should win more often than low")
                    .isGreaterThan(lowCount);

            // Sanity-check the proportions land near the analytical expectations (±5%).
            assertThat((double) highCount / trials).isCloseTo(1.0 / 1.8, offset(0.05));
            assertThat((double) midCount / trials).isCloseTo(0.7 / 1.8, offset(0.05));
            assertThat((double) lowCount / trials).isCloseTo(0.1 / 1.8, offset(0.05));
        }
    }

    @Nested
    @DisplayName("recording outcomes")
    class RecordingOutcomes {

        @Test
        @DisplayName("given a failure is recorded, then the peer's reputation drops and its breaker counts it")
        void givenFailureRecorded_thenReputationDropsAndBreakerCounts() {
            for (int i = 0; i < FAILURES_TO_OPEN; i++) {
                subject.recordFailure(PEER_1_ID);
            }

            assertThat(subject.getReputation(PEER_1_ID).rawScore()).isCloseTo(0.1, offset(0.0001));
            assertThat(subject.getCircuitBreaker(PEER_1_ID).state()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @Test
        @DisplayName("given a success is recorded, then the peer's reputation rises and its breaker closes")
        void givenSuccessRecorded_thenReputationRisesAndBreakerCloses() {
            // Degrade both trackers first: reputation saturates at 1.0, so a rise is only visible from below.
            for (int i = 0; i < FAILURES_TO_OPEN; i++) {
                subject.recordFailure(PEER_1_ID);
            }

            subject.recordSuccess(PEER_1_ID);

            assertThat(subject.getReputation(PEER_1_ID).rawScore()).isCloseTo(0.2, offset(0.0001));
            assertThat(subject.getCircuitBreaker(PEER_1_ID).state()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("given an outcome for one peer, then other peers are unaffected")
        void givenOutcomeForOnePeer_thenOtherPeersAreUnaffected() {
            subject.recordFailure(PEER_1_ID);

            assertThat(subject.getReputation(PEER_2_ID).rawScore()).isCloseTo(1.0, offset(0.0001));
            assertThat(subject.getCircuitBreaker(PEER_2_ID).state()).isEqualTo(CircuitBreaker.State.CLOSED);
        }
    }

    private void openBreaker(final String peerId) {
        final var breaker = subject.getCircuitBreaker(peerId);
        for (int i = 0; i < FAILURES_TO_OPEN; i++) {
            breaker.recordFailure();
        }
    }

    private static ClprChannel channel() {
        return ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .status(ClprChannelStatus.ACTIVE)
                .build();
    }

    private static ClprEndpoint endpoint(final String host) {
        return ClprEndpoint.newBuilder()
                .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                        .ipAddress(host)
                        .port(PEER_PORT)
                        .build())
                .tlsCertificate(Bytes.wrap(new byte[] {1, 2, 3}))
                .build();
    }
}
