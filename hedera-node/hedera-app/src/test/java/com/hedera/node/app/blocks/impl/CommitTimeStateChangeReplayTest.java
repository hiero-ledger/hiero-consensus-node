// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACCOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.BATCH_INNER;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.CHILD;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.node.app.hapi.utils.blocks.StateChangeDeltas;
import com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.workflows.handle.record.TraceDataSizeLimiter;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.config.types.StreamMode;
import com.swirlds.state.State;
import com.swirlds.state.spi.KVChangeListener;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CommitTimeStateChangeReplayTest {
    private static final String SERVICE = "TokenService";
    private static final int STATE_ID = STATE_ID_ACCOUNTS.protoOrdinal();
    private static final AccountID KEY = AccountID.newBuilder().accountNum(123).build();
    private static final Account INITIAL = Account.newBuilder()
            .accountId(KEY)
            .memo("m".repeat(200))
            .tinybarBalance(10)
            .build();
    private static final Account FIRST =
            INITIAL.copyBuilder().tinybarBalance(20).build();
    private static final Account FINAL =
            INITIAL.copyBuilder().receiverSigRequired(true).build();

    @ParameterizedTest
    @EnumSource(
            value = TransactionCategory.class,
            names = {"PRECEDING", "BATCH_INNER"})
    void childWritesCoalesceIntoOneReplayableRootUpdate(final TransactionCategory category) {
        final var harness = new Harness(
                category == BATCH_INNER ? HederaFunctionality.ATOMIC_BATCH : HederaFunctionality.CRYPTO_TRANSFER);
        harness.put(harness.root, FIRST);
        final var child = harness.child(category);
        harness.put(child, FINAL);
        child.commitFullStack();
        assertThat(harness.terminalUpdates).isZero();
        harness.root.commitFullStack();
        harness.assertReplay(1, FINAL);
    }

    @Test
    void precedingAndParentCommitsReplayInTheirOutputOrder() {
        final var harness = new Harness(HederaFunctionality.CRYPTO_TRANSFER);
        final var preceding = harness.root.createIrreversiblePrecedingBuilder();
        initialize(preceding, HederaFunctionality.NODE_STAKE_UPDATE, 1);
        harness.put(harness.root, FIRST);
        harness.root.commitTransaction(preceding);
        harness.put(harness.root, FINAL);
        harness.root.commitFullStack();
        harness.assertReplay(2, FINAL);
    }

    @Test
    void rolledBackChildWritesDoNotReachTheCommitEncoder() {
        final var harness = new Harness(HederaFunctionality.CRYPTO_TRANSFER);
        harness.put(harness.root, FIRST);
        final var child = harness.child(CHILD);
        harness.put(child, FINAL);
        child.rollbackFullStack();
        assertThat(harness.terminalUpdates).isZero();
        harness.root.commitFullStack();
        harness.assertReplay(1, FIRST);
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
        private final Map<AccountID, Account> backing = new HashMap<>(Map.of(KEY, INITIAL));
        private final SavepointStackImpl root;
        private int terminalUpdates;

        private Harness(final HederaFunctionality function) {
            final var listener = new ImmediateStateChangeListener();
            listener.beginBlock(true);
            final var accounts = new MapWritableKVState<>(STATE_ID, "ACCOUNTS", backing);
            accounts.registerListener(new KVChangeListener<>() {
                @Override
                public boolean requiresPreviousValue() {
                    return listener.requiresPreviousValue(STATE_ID);
                }

                @Override
                public void mapUpdateChange(final AccountID key, final Account value) {
                    fail("Expected previous-value callback");
                }

                @Override
                public void mapUpdateChange(final AccountID key, final Account prior, final Account value) {
                    terminalUpdates++;
                    listener.mapUpdateChange(STATE_ID, key, prior, value);
                }

                @Override
                public void mapDeleteChange(final AccountID key) {
                    listener.mapDeleteChange(STATE_ID, key);
                }
            });
            final var states = MapWritableStates.builder().state(accounts).build();
            final var state = mock(State.class);
            when(state.getWritableStates(SERVICE)).thenReturn(states);
            root = SavepointStackImpl.newRootStack(
                    state,
                    20,
                    50,
                    new BoundaryStateChangeListener((type, capacity) -> null, () -> null),
                    listener,
                    StreamMode.BLOCKS,
                    TraceDataSizeLimiter.NO_LIMIT);
            initialize(root.getBaseBuilder(StreamBuilder.class), function, 0);
        }

        private SavepointStackImpl child(final TransactionCategory category) {
            final var child = SavepointStackImpl.newChildStack(
                    root, REVERSIBLE, category, NOOP_SIGNED_TX_CUSTOMIZER, StreamMode.BLOCKS);
            initialize(child.getBaseBuilder(StreamBuilder.class), HederaFunctionality.CRYPTO_TRANSFER, 1);
            return child;
        }

        private void put(final SavepointStackImpl stack, final Account value) {
            stack.getWritableStates(SERVICE).<AccountID, Account>get(STATE_ID).put(KEY, value);
        }

        private void assertReplay(final int expectedUpdates, final Account expected) {
            final var source = root.buildHandleOutput(Instant.ofEpochSecond(100), ExchangeRateSet.DEFAULT, 1L)
                    .blockRecordSourceOrThrow();
            Object replay = INITIAL;
            int updates = 0;
            for (final var item : source.blockItems()) {
                if (!item.hasStateChanges()) continue;
                for (final var change : item.stateChangesOrThrow().stateChanges()) {
                    if (!change.hasMapUpdate()) continue;
                    final var update = change.mapUpdateOrThrow();
                    assertThat(update.partial()).isTrue();
                    replay = StateChangeDeltas.applyMap(STATE_ID, replay, update);
                    updates++;
                }
            }
            assertThat(terminalUpdates).isEqualTo(expectedUpdates);
            assertThat(updates).isEqualTo(expectedUpdates);
            assertThat(backing.get(KEY)).isEqualTo(expected);
            assertThat(replay).isEqualTo(expected);
            assertThat(source.precomputedRecords())
                    .isNotEmpty()
                    .extracting(record ->
                            record.transactionRecord().receiptOrThrow().status())
                    .containsExactlyElementsOf(source.blockItems().stream()
                            .filter(item -> item.hasTransactionResult())
                            .map(item -> item.transactionResultOrThrow().status())
                            .toList());
        }
    }
}
