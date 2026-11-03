// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.services.auxiliary.clpr.ClprMaintenanceReason.ENDPOINT_MANIFEST_RECONCILIATION;
import static com.hedera.hapi.services.auxiliary.clpr.ClprMaintenanceReason.GENESIS_INITIALIZATION;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.clpr.ClprMaintenanceTransactionBody;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema;
import com.hedera.node.app.workflows.handle.record.SystemTransactions;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class ClprMaintenanceTransactionsTest {
    private static final Instant BEFORE = Instant.ofEpochSecond(1234567);
    private static final Instant CONSENSUS_TIME = BEFORE.minusNanos(100);
    private static final TransactionID TRANSACTION_ID = TransactionID.newBuilder()
            .accountID(AccountID.newBuilder().accountNum(50).build())
            .transactionValidStart(asTimestamp(CONSENSUS_TIME))
            .nonce(1)
            .build();
    private final ConfigProvider configProvider = mock(ConfigProvider.class);
    private final BlockStreamManager blockStreamManager = mock(BlockStreamManager.class);
    private final SystemTransactions systemTransactions = mock(SystemTransactions.class);
    private final ExchangeRateManager exchangeRateManager = mock(ExchangeRateManager.class);
    private final ClprMaintenanceTransactions subject = new ClprMaintenanceTransactions(
            configProvider, blockStreamManager, systemTransactions, exchangeRateManager);
    private WritableStates states;

    @BeforeEach
    void setUp() {
        configure("BLOCKS");
        given(systemTransactions.nextReservedSystemTimeFor(BEFORE)).willReturn(CONSENSUS_TIME);
        given(systemTransactions.nextSystemTransactionIdAt(CONSENSUS_TIME)).willReturn(TRANSACTION_ID);
        given(exchangeRateManager.exchangeRates()).willReturn(ExchangeRateSet.DEFAULT);
        states = MapWritableStates.builder()
                .state(singleton(LEDGER_CONFIGURATION_STATE_ID, (ClprLedgerConfiguration) null))
                .state(singleton(ENDPOINT_MANIFEST_STATE_ID, (ClprEndpointManifest) null))
                .state(singleton(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID, (ClprEndpointManifestConstruction) null))
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOCKS", "BOTH"})
    void seedsAllSingletonsWithOneSuccessfulTransactionAndNoDuplicateOnRetry(final String mode) throws Exception {
        configure(mode);
        initialize();
        initialize();

        final List<BlockItem> items = capturedItems();
        assertThat(items).hasSize(3);
        final var signed = SignedTransaction.PROTOBUF.parse(items.getFirst().signedTransactionOrThrow());
        final var body = TransactionBody.PROTOBUF.parse(signed.bodyBytes());
        // Only the reason is in the body; the state changes below carry the singleton values
        assertThat(body.clprMaintenanceOrThrow())
                .isEqualTo(ClprMaintenanceTransactionBody.newBuilder()
                        .reason(GENESIS_INITIALIZATION)
                        .build());
        // A non-zero nonce keeps block stream consumers from treating this as a transaction from an event
        assertThat(body.transactionIDOrThrow()).isEqualTo(TRANSACTION_ID);
        assertThat(body.memo()).isEmpty();
        assertThat(items.get(1).transactionResultOrThrow().status()).isEqualTo(SUCCESS);
        assertThat(items.get(1).transactionResultOrThrow().consensusTimestamp()).isEqualTo(asTimestamp(CONSENSUS_TIME));
        final var changes = items.getLast().stateChangesOrThrow();
        assertThat(changes.consensusTimestamp()).isEqualTo(asTimestamp(CONSENSUS_TIME));
        assertThat(changes.stateChanges())
                .extracting(change -> change.stateId())
                .containsExactlyInAnyOrder(
                        LEDGER_CONFIGURATION_STATE_ID,
                        ENDPOINT_MANIFEST_STATE_ID,
                        ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID);
        // Replaying each emitted value reconstructs every seeded singleton, including the empty construction.
        for (final var change : changes.stateChanges()) {
            final Object replayed = change.singletonUpdateOrThrow().newValue().value();
            assertThat(replayed).isEqualTo(states.getSingleton(change.stateId()).get());
        }
        assertThat(states.getSingleton(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID).get())
                .isEqualTo(ClprEndpointManifestConstruction.DEFAULT);
        verifyNoMoreInteractions(blockStreamManager);
        verify(systemTransactions).nextReservedSystemTimeFor(BEFORE);
        verify(systemTransactions).nextSystemTransactionIdAt(CONSENSUS_TIME);
    }

    @Test
    void repairsOnlyMissingConstruction() {
        states.<ClprLedgerConfiguration>getSingleton(LEDGER_CONFIGURATION_STATE_ID)
                .put(ClprLedgerConfiguration.DEFAULT);
        states.<ClprEndpointManifest>getSingleton(ENDPOINT_MANIFEST_STATE_ID).put(ClprEndpointManifest.DEFAULT);
        initialize();
        assertThat(capturedItems().getLast().stateChangesOrThrow().stateChanges())
                .singleElement()
                .satisfies(change -> {
                    assertThat(change.stateId()).isEqualTo(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID);
                    assertThat(change.singletonUpdateOrThrow().clprEndpointManifestConstructionValue())
                            .isEqualTo(ClprEndpointManifestConstruction.DEFAULT);
                });
    }

    @Test
    void manifestMaintenanceUsesReasonAndExplicitConstructionClear() throws Exception {
        states.<ClprEndpointManifestConstruction>getSingleton(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID)
                .put(ClprEndpointManifestConstruction.newBuilder()
                        .constructionId(2)
                        .build());
        subject.perform(
                states,
                BEFORE,
                staged -> staged.<ClprEndpointManifestConstruction>getSingleton(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID)
                        .put(ClprEndpointManifestConstruction.DEFAULT),
                ENDPOINT_MANIFEST_RECONCILIATION);
        final var items = capturedItems();
        final var body = TransactionBody.PROTOBUF.parse(SignedTransaction.PROTOBUF
                .parse(items.getFirst().signedTransactionOrThrow())
                .bodyBytes());
        assertThat(body.clprMaintenanceOrThrow().reason()).isEqualTo(ENDPOINT_MANIFEST_RECONCILIATION);
        assertThat(items.getLast().stateChangesOrThrow().stateChanges()).hasSize(1);
        assertThat(states.getSingleton(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID).get())
                .isEqualTo(ClprEndpointManifestConstruction.DEFAULT);
    }

    @Test
    void leavesStateUntouchedWhenReservedTimesAreExhausted() {
        given(systemTransactions.nextReservedSystemTimeFor(BEFORE)).willThrow(new IllegalStateException("exhausted"));
        assertThatThrownBy(this::initialize).isInstanceOf(IllegalStateException.class);
        for (final int id : states.stateIds()) {
            assertThat(states.getSingleton(id).get()).isNull();
        }
        verifyNoInteractions(blockStreamManager);
    }

    @Test
    void leavesStateUntouchedWhenMaintenanceFails() {
        assertThatThrownBy(() -> subject.perform(
                        states,
                        BEFORE,
                        staged -> {
                            staged.<ClprEndpointManifest>getSingleton(ENDPOINT_MANIFEST_STATE_ID)
                                    .put(ClprEndpointManifest.DEFAULT);
                            throw new IllegalStateException("failed");
                        },
                        ENDPOINT_MANIFEST_RECONCILIATION))
                .isInstanceOf(IllegalStateException.class);
        assertThat(states.getSingleton(ENDPOINT_MANIFEST_STATE_ID).get()).isNull();
        verifyNoInteractions(blockStreamManager, systemTransactions);
    }

    @Test
    void preservesRecordOnlyMaintenanceWithoutBlockOutput() {
        configure("RECORDS");
        initialize();
        assertThat(states.getSingleton(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID).get())
                .isEqualTo(ClprEndpointManifestConstruction.DEFAULT);
        verifyNoInteractions(blockStreamManager, systemTransactions);
    }

    private void initialize() {
        subject.perform(
                states,
                BEFORE,
                staged -> V0780ClprSchema.initializeSingletons(staged, configProvider.getConfiguration()),
                GENESIS_INITIALIZATION);
    }

    @SuppressWarnings("unchecked")
    private List<BlockItem> capturedItems() {
        final ArgumentCaptor<List<BlockItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(blockStreamManager).writeSavepointItems(captor.capture(), eq(CONSENSUS_TIME));
        // Replay reads serialized block items; in particular, an empty construction must retain oneof presence.
        return captor.getValue().stream()
                .map(item -> {
                    try {
                        return BlockItem.PROTOBUF.parse(BlockItem.PROTOBUF.toBytes(item));
                    } catch (com.hedera.pbj.runtime.ParseException e) {
                        throw new AssertionError(e);
                    }
                })
                .toList();
    }

    private void configure(final String mode) {
        given(configProvider.getConfiguration())
                .willReturn(new VersionedConfigImpl(
                        HederaTestConfigBuilder.create()
                                .withValue("blockStream.streamMode", mode)
                                .withValue("accounts.systemAdmin", 50)
                                .withValue("clpr.chainId", "test-chain")
                                .getOrCreateConfig(),
                        1));
    }

    private static <T> FunctionWritableSingletonState<T> singleton(final int id, final T initial) {
        final var value = new AtomicReference<>(initial);
        return new FunctionWritableSingletonState<>(id, "CLPR_" + id, value::get, value::set);
    }
}
