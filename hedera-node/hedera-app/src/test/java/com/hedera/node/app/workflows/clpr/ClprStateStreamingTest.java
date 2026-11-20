// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_UPDATE_LEDGER_CONFIGURATION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.ImmediateStateChangeListener;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.node.app.spi.metrics.StoreMetricsService;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.workflows.handle.record.TraceDataSizeLimiter;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.config.types.StreamMode;
import com.swirlds.state.State;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ClprStateStreamingTest {
    static Stream<Arguments> singletonUpdates() {
        return Stream.of(
                Arguments.of(
                        LEDGER_CONFIGURATION_STATE_ID,
                        ClprLedgerConfiguration.DEFAULT,
                        ClprLedgerConfiguration.newBuilder().chainId("updated").build()),
                Arguments.of(
                        ENDPOINT_MANIFEST_STATE_ID,
                        ClprEndpointManifest.DEFAULT,
                        ClprEndpointManifest.newBuilder().version(2).build()),
                Arguments.of(
                        ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID,
                        ClprEndpointManifestConstruction.DEFAULT,
                        ClprEndpointManifestConstruction.newBuilder()
                                .constructionId(2)
                                .build()));
    }

    @ParameterizedTest
    @MethodSource("singletonUpdates")
    void commitAssociatesSingletonWithTransactionAndRollbackDoesNotLeak(
            final int id, final Object original, final Object updated) {
        final var immediate = new ImmediateStateChangeListener();
        final var boundary =
                new BoundaryStateChangeListener(mock(StoreMetricsService.class), HederaTestConfigBuilder::createConfig);
        final var value = new AtomicReference<>(original);
        final var singleton = new FunctionWritableSingletonState<>(id, "CLPR", value::get, value::set);
        singleton.registerListener(change -> immediate.singletonUpdateChange(id, change));
        singleton.registerListener(change -> boundary.singletonUpdateChange(id, change));
        final var states = MapWritableStates.builder().state(singleton).build();
        final var state = mock(State.class);
        given(state.getWritableStates(ClprService.NAME)).willReturn(states);
        given(state.getReadableStates(ClprService.NAME)).willReturn(states);
        final var stack = SavepointStackImpl.newRootStack(
                state, 3, 50, boundary, immediate, StreamMode.BLOCKS, TraceDataSizeLimiter.NO_LIMIT);
        stack.getBaseBuilder(StreamBuilder.class)
                .signedTx(SignedTransaction.DEFAULT)
                .status(SUCCESS)
                .exchangeRate(ExchangeRateSet.DEFAULT)
                .functionality(CLPR_UPDATE_LEDGER_CONFIGURATION)
                .transactionID(TransactionID.newBuilder()
                        .accountID(AccountID.newBuilder().accountNum(50).build())
                        .build());
        stack.getWritableStates(ClprService.NAME).getSingleton(id).put(updated);
        stack.commitFullStack();
        final var output = stack.buildHandleOutput(Instant.ofEpochSecond(1234), ExchangeRateSet.DEFAULT);
        final var changes = output.blockRecordSourceOrThrow().blockItems().stream()
                .filter(BlockItem::hasStateChanges)
                .flatMap(item -> item.stateChangesOrThrow().stateChanges().stream())
                .toList();
        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.stateId()).isEqualTo(id);
            assertThat(change.singletonUpdateOrThrow().newValue().value()).isEqualTo(updated);
        });
        assertThat(boundary.allStateChanges()).isEmpty();

        // Subsequent rolled-back changes must not reach either listener or the already-built output.
        immediate.reset(null);
        final var rollbackStack = SavepointStackImpl.newRootStack(
                state, 3, 50, boundary, immediate, StreamMode.BLOCKS, TraceDataSizeLimiter.NO_LIMIT);
        rollbackStack.getWritableStates(ClprService.NAME).getSingleton(id).put(original);
        rollbackStack.rollbackFullStack();
        assertThat(value.get()).isEqualTo(updated);
        assertThat(immediate.getStateChanges()).isEmpty();
        assertThat(boundary.allStateChanges()).isEmpty();
    }
}
