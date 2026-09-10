// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_SEED_ENDPOINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_TOO_MANY_SEED_ENDPOINTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CLPR_CONFIGURATION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.clpr.ClprUpdateLedgerConfigurationTransactionBody;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.service.clpr.impl.WritableLedgerConfigurationStore;
import com.hedera.node.app.service.clpr.impl.handlers.ClprUpdateLedgerConfigurationHandler;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprUpdateLedgerConfigurationHandlerTest {

    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    @Mock(strictness = LENIENT)
    private PureChecksContext pureChecksContext;

    @Mock(strictness = LENIENT)
    private StoreFactory storeFactory;

    @Mock(strictness = LENIENT)
    private WritableLedgerConfigurationStore configStore;

    @Mock(strictness = LENIENT)
    private ClprStateProofManager stateProofManager;

    private ClprUpdateLedgerConfigurationHandler subject;

    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);
    private static final String CHAIN_ID = "hiero:unit";
    private static final int PROTOCOL_VERSION = 1;
    private static final Bytes SERVICE_ADDRESS = Bytes.wrap(new byte[] {0, 0, 1});
    private static final Bytes TLS_CERT = Bytes.wrap(new byte[] {1, 2, 3, 4});
    private static final Bytes LEDGER_ID = Bytes.wrap(new byte[] {0x11, 0x22, 0x33, 0x44});

    @BeforeEach
    void setUp() {
        subject = new ClprUpdateLedgerConfigurationHandler(stateProofManager);
        given(stateProofManager.latestLedgerId()).willReturn(Bytes.EMPTY);
    }

    @Test
    @DisplayName("should reject transaction body with no configuration")
    void rejectsMissingConfiguration() {
        final var txnBody = TransactionBody.newBuilder()
                .clprUpdateLedgerConfiguration(ClprUpdateLedgerConfigurationTransactionBody.newBuilder()
                        .build())
                .build();
        given(pureChecksContext.body()).willReturn(txnBody);

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_TRANSACTION_BODY);
    }

    @Test
    @DisplayName("should reject configuration with zero max_messages_per_bundle")
    void rejectsZeroMaxMessagesPerBundle() {
        final var config = validConfigBuilder()
                .throttles(validThrottlesBuilder().maxMessagesPerBundle(0).build())
                .build();
        final var txnBody = txnBodyWith(config);
        given(pureChecksContext.body()).willReturn(txnBody);

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_CLPR_CONFIGURATION);
    }

    @Test
    @DisplayName("should reject configuration with zero max_message_payload_bytes")
    void rejectsZeroMaxPayloadBytes() {
        final var config = validConfigBuilder()
                .throttles(validThrottlesBuilder().maxMessagePayloadBytes(0).build())
                .build();
        final var txnBody = txnBodyWith(config);
        given(pureChecksContext.body()).willReturn(txnBody);

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_CLPR_CONFIGURATION);
    }

    @Test
    @DisplayName("should reject configuration with zero max_queue_depth")
    void rejectsZeroMaxQueueDepth() {
        final var config = validConfigBuilder()
                .throttles(validThrottlesBuilder().maxQueueDepth(0).build())
                .build();
        final var txnBody = txnBodyWith(config);
        given(pureChecksContext.body()).willReturn(txnBody);

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_CLPR_CONFIGURATION);
    }

    @Test
    @DisplayName("should reject configuration with zero max_gas_per_message")
    void rejectsZeroMaxGasPerMessage() {
        final var config = validConfigBuilder()
                .throttles(validThrottlesBuilder().maxGasPerMessage(0).build())
                .build();
        final var txnBody = txnBodyWith(config);
        given(pureChecksContext.body()).willReturn(txnBody);

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_CLPR_CONFIGURATION);
    }

    @Test
    @DisplayName("should reject configuration with zero max_sync_bytes")
    void rejectsZeroMaxSyncBytes() {
        final var config = validConfigBuilder()
                .throttles(validThrottlesBuilder().maxSyncBytes(0).build())
                .build();
        final var txnBody = txnBodyWith(config);
        given(pureChecksContext.body()).willReturn(txnBody);

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_CLPR_CONFIGURATION);
    }

    @Test
    @DisplayName("should reject max_sync_bytes not larger than max_message_payload_bytes")
    void rejectsMaxSyncBytesNotLargerThanPayloadBytes() {
        final var config = validConfigBuilder()
                .throttles(validThrottlesBuilder()
                        .maxMessagePayloadBytes(65_536)
                        .maxSyncBytes(65_536L)
                        .build())
                .build();
        final var txnBody = txnBodyWith(config);
        given(pureChecksContext.body()).willReturn(txnBody);

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_CLPR_CONFIGURATION);
    }

    @Test
    @DisplayName("should reject max_sync_bytes below the deadlock-guard floor (spec §1.1)")
    void rejectsMaxSyncBytesBelowDeadlockGuardFloor() {
        // maxSyncBytes only just exceeds maxMessagePayloadBytes — not enough room for the proof
        // envelope, so the first oversized message would stall the Channel. Floor is
        // maxMessagePayloadBytes + 65_536.
        final var config = validConfigBuilder()
                .throttles(validThrottlesBuilder()
                        .maxMessagePayloadBytes(65_536)
                        .maxSyncBytes(65_537L)
                        .build())
                .build();
        final var txnBody = txnBodyWith(config);
        given(pureChecksContext.body()).willReturn(txnBody);

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_CLPR_CONFIGURATION);
    }

    @Test
    @DisplayName("should reject configuration with default (all-zero) throttles")
    void rejectsDefaultThrottles() {
        final var config = validConfigBuilder().throttles(ClprThrottles.DEFAULT).build();
        final var txnBody = txnBodyWith(config);
        given(pureChecksContext.body()).willReturn(txnBody);

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_CLPR_CONFIGURATION);
    }

    @Test
    @DisplayName("should reject configuration with no throttles specified")
    void rejectsNoThrottles() {
        final var config = ClprLedgerConfiguration.newBuilder()
                .chainId(CHAIN_ID)
                .serviceAddress(SERVICE_ADDRESS)
                .endpoints(List.of(validEndpoint()))
                .build();
        final var txnBody = txnBodyWith(config);
        given(pureChecksContext.body()).willReturn(txnBody);

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_CLPR_CONFIGURATION);
    }

    @Test
    @DisplayName("should accept more than 10 endpoints when max_local_endpoints is zero")
    void acceptsMoreThanTenEndpointsWhenMaxLocalEndpointsIsZero() throws PreCheckException {
        final List<ClprEndpoint> endpoints = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            endpoints.add(validEndpoint());
        }
        final var config = validConfigBuilder().endpoints(endpoints).build();
        final var txnBody = txnBodyWith(config);
        given(pureChecksContext.body()).willReturn(txnBody);

        subject.pureChecks(pureChecksContext);
    }

    @Test
    @DisplayName("should reject endpoints exceeding non-zero max_local_endpoints")
    void rejectsEndpointsExceedingMaxLocalEndpoints() {
        final List<ClprEndpoint> endpoints = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            endpoints.add(validEndpoint());
        }
        final var config = validConfigBuilder()
                .throttles(validThrottlesBuilder().maxLocalEndpoints(2).build())
                .endpoints(endpoints)
                .build();
        final var txnBody = txnBodyWith(config);
        given(pureChecksContext.body()).willReturn(txnBody);

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), CLPR_TOO_MANY_SEED_ENDPOINTS);
    }

    @Test
    @DisplayName("should accept endpoints equal to non-zero max_local_endpoints")
    void acceptsEndpointsEqualToMaxLocalEndpoints() throws PreCheckException {
        final List<ClprEndpoint> endpoints = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            endpoints.add(validEndpoint());
        }
        final var config = validConfigBuilder()
                .throttles(validThrottlesBuilder().maxLocalEndpoints(3).build())
                .endpoints(endpoints)
                .build();
        final var txnBody = txnBodyWith(config);
        given(pureChecksContext.body()).willReturn(txnBody);

        subject.pureChecks(pureChecksContext);
    }

    @Test
    @DisplayName("should reject endpoint missing service_endpoint")
    void rejectsEndpointMissingServiceEndpoint() {
        final var badEndpoint =
                ClprEndpoint.newBuilder().tlsCertificate(TLS_CERT).build();
        final var config = validConfigBuilder().endpoints(List.of(badEndpoint)).build();
        final var txnBody = txnBodyWith(config);
        given(pureChecksContext.body()).willReturn(txnBody);

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), CLPR_INVALID_SEED_ENDPOINT);
    }

    @Test
    @DisplayName("should reject endpoint missing tls_certificate")
    void rejectsEndpointMissingTlsCert() {
        final var badEndpoint = ClprEndpoint.newBuilder()
                .serviceEndpoint(validServiceEndpoint())
                .build();
        final var config = validConfigBuilder().endpoints(List.of(badEndpoint)).build();
        final var txnBody = txnBodyWith(config);
        given(pureChecksContext.body()).willReturn(txnBody);

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), CLPR_INVALID_SEED_ENDPOINT);
    }

    @Test
    @DisplayName("should accept valid configuration in pureChecks")
    void acceptsValidConfiguration() throws PreCheckException {
        final var config = validConfigBuilder().build();
        final var txnBody = txnBodyWith(config);
        given(pureChecksContext.body()).willReturn(txnBody);

        subject.pureChecks(pureChecksContext);
        // No exception means success
    }

    @Test
    @DisplayName("should reject when CLPR is not enabled")
    void rejectsWhenNotEnabled() {
        final var configuration = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", false)
                .getOrCreateConfig();
        setupHandleContext(configuration);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(new org.assertj.core.api.Condition<>(
                        e -> ((HandleException) e).getStatus() == CLPR_NOT_ENABLED, "CLPR_NOT_ENABLED"));
    }

    @Test
    @DisplayName("should update configuration preserving immutable fields from genesis")
    void updatesConfigurationPreservingImmutableFields() {
        final var configuration =
                HederaTestConfigBuilder.create().withValue("clpr.enabled", true).getOrCreateConfig();
        setupHandleContext(configuration);

        final var newConfig = validConfigBuilder().build();
        final var txnBody = txnBodyWith(newConfig);
        given(handleContext.body()).willReturn(txnBody);

        subject.handle(handleContext);

        final var captor = ArgumentCaptor.forClass(ClprLedgerConfiguration.class);
        verify(configStore).put(captor.capture());
        final var saved = captor.getValue();
        // Immutable fields come from the existing (genesis) configuration
        assertThat(saved.protocolVersion()).isEqualTo(PROTOCOL_VERSION);
        assertThat(saved.chainId()).isEqualTo(CHAIN_ID);
        // Mutable fields come from the supplied configuration
        assertThat(saved.serviceAddress()).isEqualTo(SERVICE_ADDRESS);
        assertThat(saved.timestamp().seconds()).isEqualTo(1_234_567L);
        assertThat(saved.timestamp().nanos()).isEqualTo(890);
    }

    @Test
    @DisplayName("should populate initial_trust_anchor from the latest signed snapshot's ledger_id")
    void populatesTrustAnchorFromLatestLedgerId() {
        final var configuration =
                HederaTestConfigBuilder.create().withValue("clpr.enabled", true).getOrCreateConfig();
        setupHandleContext(configuration);
        given(stateProofManager.latestLedgerId()).willReturn(LEDGER_ID);

        final var newConfig = validConfigBuilder().build();
        given(handleContext.body()).willReturn(txnBodyWith(newConfig));

        subject.handle(handleContext);

        final var captor = ArgumentCaptor.forClass(ClprLedgerConfiguration.class);
        verify(configStore).put(captor.capture());
        final var saved = captor.getValue();
        assertThat(saved.initialTrustAnchor()).isEqualTo(LEDGER_ID);
        assertThat(saved.initialTrustAnchorId()).isEqualTo(LEDGER_ID);
    }

    @Test
    @DisplayName("should preserve existing initial_trust_anchor when ledger_id is not yet available")
    void preservesExistingTrustAnchorWhenLedgerIdEmpty() {
        final var configuration =
                HederaTestConfigBuilder.create().withValue("clpr.enabled", true).getOrCreateConfig();
        final var priorAnchor = Bytes.wrap(new byte[] {0x55, 0x66});
        final var existing = ClprLedgerConfiguration.newBuilder()
                .chainId(CHAIN_ID)
                .protocolVersion(PROTOCOL_VERSION)
                .initialTrustAnchor(priorAnchor)
                .initialTrustAnchorId(priorAnchor)
                .build();
        given(handleContext.configuration()).willReturn(configuration);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableLedgerConfigurationStore.class))
                .willReturn(configStore);
        given(configStore.getConfiguration()).willReturn(existing);
        given(handleContext.consensusNow()).willReturn(CONSENSUS_NOW);
        given(stateProofManager.latestLedgerId()).willReturn(Bytes.EMPTY);

        final var newConfig = validConfigBuilder().build();
        given(handleContext.body()).willReturn(txnBodyWith(newConfig));

        subject.handle(handleContext);

        final var captor = ArgumentCaptor.forClass(ClprLedgerConfiguration.class);
        verify(configStore).put(captor.capture());
        final var saved = captor.getValue();
        assertThat(saved.initialTrustAnchor()).isEqualTo(priorAnchor);
        assertThat(saved.initialTrustAnchorId()).isEqualTo(priorAnchor);
    }

    // --- Helpers ---

    private void setupHandleContext(final Configuration configuration) {
        final var existing = genesisConfig();
        given(handleContext.configuration()).willReturn(configuration);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableLedgerConfigurationStore.class))
                .willReturn(configStore);
        given(configStore.getConfiguration()).willReturn(existing);
        given(handleContext.consensusNow()).willReturn(CONSENSUS_NOW);
    }

    private static ClprLedgerConfiguration genesisConfig() {
        return ClprLedgerConfiguration.newBuilder()
                .chainId(CHAIN_ID)
                .protocolVersion(PROTOCOL_VERSION)
                .build();
    }

    private static TransactionBody txnBodyWith(final ClprLedgerConfiguration config) {
        return TransactionBody.newBuilder()
                .clprUpdateLedgerConfiguration(ClprUpdateLedgerConfigurationTransactionBody.newBuilder()
                        .configuration(config)
                        .build())
                .build();
    }

    private static ClprLedgerConfiguration.Builder validConfigBuilder() {
        return ClprLedgerConfiguration.newBuilder()
                .chainId(CHAIN_ID)
                .serviceAddress(SERVICE_ADDRESS)
                .throttles(validThrottlesBuilder().build())
                .endpoints(List.of(validEndpoint()));
    }

    private static ClprThrottles.Builder validThrottlesBuilder() {
        return ClprThrottles.newBuilder()
                .maxMessagesPerBundle(100)
                .maxMessagePayloadBytes(65536)
                .maxGasPerMessage(1_000_000L)
                .maxQueueDepth(1000)
                .maxSyncBytes(1_048_576L);
    }

    private static ClprEndpoint validEndpoint() {
        return ClprEndpoint.newBuilder()
                .serviceEndpoint(validServiceEndpoint())
                .tlsCertificate(TLS_CERT)
                .build();
    }

    private static ClprServiceEndpoint validServiceEndpoint() {
        return ClprServiceEndpoint.newBuilder()
                .ipAddress("192.168.1.1")
                .port(50211)
                .build();
    }
}
