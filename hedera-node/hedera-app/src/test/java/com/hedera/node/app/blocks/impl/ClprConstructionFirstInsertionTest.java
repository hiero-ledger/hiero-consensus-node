// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACCOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.CHILD;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.node.app.hapi.utils.blocks.StateChangeDeltas;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.workflows.handle.record.TraceDataSizeLimiter;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.CopyBuilderTracked;
import com.swirlds.state.State;
import com.swirlds.state.spi.KVChangeListener;
import com.swirlds.state.spi.SingletonChangeListener;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClprConstructionFirstInsertionTest {
    private static final String CLPR_SERVICE = "ClprService";
    private static final String TOKEN_SERVICE = "TokenService";
    private static final int ACCOUNTS = STATE_ID_ACCOUNTS.protoOrdinal();
    private static final AccountID KEY = AccountID.newBuilder().accountNum(123).build();
    private static final Account ACCOUNT = Account.newBuilder().accountId(KEY).build();
    private static final ClprEndpointManifestConstruction INITIAL = ClprEndpointManifestConstruction.DEFAULT
            .copyBuilder()
            .constructionId(1)
            .targetNodeIds(LongStream.range(0, 100).boxed().toList())
            .build();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstInsertionIsCapturedBeforeALaterTransactionInsertsAnAccount(final boolean deltasEnabled) {
        final var harness = new Harness(deltasEnabled);
        assertThat(harness.storedConstruction.get()).isNull();
        final var preceding = harness.root.createIrreversiblePrecedingBuilder();
        initialize(preceding, HederaFunctionality.NODE_STAKE_UPDATE, 1);
        harness.putConstruction(harness.root, INITIAL);
        harness.root.commitTransaction(preceding);
        harness.putAccount(harness.root);
        harness.root.commitFullStack();

        final var changes = harness.outputChanges();
        assertThat(changes)
                .extracting(StateChange::stateId)
                .containsExactly(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID, ACCOUNTS);
        final var insertion = changes.getFirst().singletonUpdateOrThrow();
        assertThat(insertion.partial()).isFalse();
        assertThat(insertion.clprEndpointManifestConstructionValueOrThrow()).isEqualTo(INITIAL);
        // Emitted values carry no copy-builder provenance, whether or not the type is tracked.
        final var emitted = insertion.clprEndpointManifestConstructionValueOrThrow();
        assertThat(CopyBuilderTracked.untracked(emitted)).isSameAs(emitted);
        assertThat(harness.boundary.allStateChanges()).isEmpty();
        assertThat(harness.physicalInsertions).containsExactly("construction", "account");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializedSingletonChangesFollowMapChanges(final boolean deltasEnabled) {
        final var harness = new Harness(deltasEnabled);
        harness.storedConstruction.set(ClprEndpointManifestConstruction.DEFAULT);
        harness.putConstruction(harness.root, INITIAL);
        harness.putAccount(harness.root);
        harness.root.commitFullStack();

        assertThat(harness.outputChanges())
                .extracting(StateChange::stateId)
                .containsExactly(ACCOUNTS, ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID);
        assertThat(harness.physicalInsertions).containsExactly("account");
        assertThat(harness.boundary.allStateChanges()).isEmpty();
    }

    @Test
    void laterReplacementsAreImmediateAndUseTheInsertedValueAsBaseline() {
        final var harness = new Harness(true);
        final var preceding = harness.root.createIrreversiblePrecedingBuilder();
        initialize(preceding, HederaFunctionality.NODE_STAKE_UPDATE, 1);
        harness.putConstruction(harness.root, INITIAL);
        harness.root.commitTransaction(preceding);
        final var updated = INITIAL.copyBuilder().graceExtensionsUsed(1).build();
        harness.putConstruction(harness.root, updated);
        harness.root.commitFullStack();

        assertThat(harness.outputChanges()).hasSize(2);
        assertThat(harness.boundary.allStateChanges()).isEmpty();
        final var update = harness.outputChanges().getLast().singletonUpdateOrThrow();
        assertThat(update.partial()).isTrue();
        assertThat(StateChangeDeltas.applySingleton(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID, INITIAL, update))
                .isEqualTo(updated);
    }

    @Test
    void openingAfterAStoredDefaultIsAnImmediateReplacement() {
        final var harness = new Harness(true);
        harness.storedConstruction.set(ClprEndpointManifestConstruction.DEFAULT);
        harness.putConstruction(harness.root, INITIAL);
        harness.root.commitFullStack();

        assertThat(harness.outputChanges()).hasSize(1);
        assertThat(harness.physicalInsertions).isEmpty();
        assertThat(harness.boundary.allStateChanges()).isEmpty();
        assertThat(harness.outputChanges()
                        .getFirst()
                        .singletonUpdateOrThrow()
                        .clprEndpointManifestConstructionValueOrThrow())
                .isEqualTo(INITIAL);
    }

    @Test
    void rolledBackFirstInsertionIsNeverCapturedOrStored() {
        final var harness = new Harness(true);
        final var child = SavepointStackImpl.newChildStack(
                harness.root, REVERSIBLE, CHILD, NOOP_SIGNED_TX_CUSTOMIZER, StreamMode.BLOCKS);
        initialize(child.getBaseBuilder(StreamBuilder.class), HederaFunctionality.CRYPTO_TRANSFER, 1);
        harness.putConstruction(child, INITIAL);
        child.rollbackFullStack();
        harness.putAccount(harness.root);
        harness.root.commitFullStack();

        assertThat(harness.outputChanges()).extracting(StateChange::stateId).containsExactly(ACCOUNTS);
        assertThat(harness.storedConstruction.get()).isNull();
        assertThat(harness.physicalInsertions).containsExactly("account");
        assertThat(harness.boundary.allStateChanges()).isEmpty();
    }

    private static void initialize(final StreamBuilder builder, final HederaFunctionality function, final int nonce) {
        builder.functionality(function)
                .signedTx(SignedTransaction.DEFAULT)
                .status(SUCCESS)
                .exchangeRate(ExchangeRateSet.DEFAULT)
                .transactionID(TransactionID.newBuilder()
                        .accountID(KEY)
                        .transactionValidStart(new Timestamp(99, 0))
                        .nonce(nonce)
                        .build());
    }

    private static class Harness {
        private final ImmediateStateChangeListener immediate = new ImmediateStateChangeListener();
        private final BoundaryStateChangeListener boundary =
                new BoundaryStateChangeListener((type, capacity) -> null, () -> null);
        private final AtomicReference<ClprEndpointManifestConstruction> storedConstruction = new AtomicReference<>();
        private final List<String> physicalInsertions = new ArrayList<>();
        private final SavepointStackImpl root;

        private Harness(final boolean deltasEnabled) {
            immediate.beginBlock(deltasEnabled);
            boundary.beginBlock(deltasEnabled);
            final var construction = new FunctionWritableSingletonState<>(
                    ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID,
                    "ENDPOINT_MANIFEST_CONSTRUCTION",
                    storedConstruction::get,
                    value -> {
                        if (storedConstruction.get() == null) physicalInsertions.add("construction");
                        storedConstruction.set(value);
                    });
            construction.registerListener(new SingletonChangeListener<>() {
                @Override
                public boolean requiresPreviousValue() {
                    return immediate.requiresPreviousValue(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID);
                }

                @Override
                public void singletonUpdateChange(final ClprEndpointManifestConstruction value) {
                    boundary.singletonUpdateChange(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID, value);
                    immediate.singletonUpdateChange(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID, value);
                }

                @Override
                public void singletonUpdateChange(
                        final ClprEndpointManifestConstruction prior, final ClprEndpointManifestConstruction value) {
                    boundary.singletonUpdateChange(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID, prior, value);
                    immediate.singletonUpdateChange(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID, prior, value);
                }
            });
            final var backingAccounts = new HashMap<AccountID, Account>();
            final var accounts = new MapWritableKVState<>(ACCOUNTS, "ACCOUNTS", backingAccounts) {
                @Override
                protected void putIntoDataSource(final AccountID key, final Account value) {
                    if (!backingAccounts.containsKey(key)) physicalInsertions.add("account");
                    super.putIntoDataSource(key, value);
                }
            };
            accounts.registerListener(new KVChangeListener<>() {
                @Override
                public boolean requiresPreviousValue() {
                    return immediate.requiresPreviousValue(ACCOUNTS);
                }

                @Override
                public void mapUpdateChange(final AccountID key, final Account value) {
                    immediate.mapUpdateChange(ACCOUNTS, key, value);
                }

                @Override
                public void mapUpdateChange(final AccountID key, final Account prior, final Account value) {
                    immediate.mapUpdateChange(ACCOUNTS, key, prior, value);
                }

                @Override
                public void mapDeleteChange(final AccountID key) {}
            });
            final var state = mock(State.class);
            when(state.getWritableStates(CLPR_SERVICE))
                    .thenReturn(MapWritableStates.builder().state(construction).build());
            when(state.getWritableStates(TOKEN_SERVICE))
                    .thenReturn(MapWritableStates.builder().state(accounts).build());
            root = SavepointStackImpl.newRootStack(
                    state, 20, 50, boundary, immediate, StreamMode.BLOCKS, TraceDataSizeLimiter.NO_LIMIT);
            initialize(root.getBaseBuilder(StreamBuilder.class), HederaFunctionality.CRYPTO_TRANSFER, 0);
        }

        private void putConstruction(final SavepointStackImpl stack, final ClprEndpointManifestConstruction value) {
            stack.getWritableStates(CLPR_SERVICE)
                    .<ClprEndpointManifestConstruction>getSingleton(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID)
                    .put(value);
        }

        private void putAccount(final SavepointStackImpl stack) {
            stack.getWritableStates(TOKEN_SERVICE)
                    .<AccountID, Account>get(ACCOUNTS)
                    .put(KEY, ACCOUNT);
        }

        private List<StateChange> outputChanges() {
            return root
                    .buildHandleOutput(Instant.ofEpochSecond(100), ExchangeRateSet.DEFAULT, 1L)
                    .blockRecordSourceOrThrow()
                    .blockItems()
                    .stream()
                    .filter(item -> item.hasStateChanges())
                    .flatMap(item -> item.stateChangesOrThrow().stateChanges().stream())
                    .toList();
        }
    }
}
