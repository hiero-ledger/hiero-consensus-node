// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_MAINTENANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.blocks.impl.BoundaryStateChangeListener.singletonUpdateChangeValueFor;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.NODE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.IRREVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static com.hedera.node.config.types.StreamMode.RECORDS;

import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.clpr.ClprMaintenanceReason;
import com.hedera.hapi.services.auxiliary.clpr.ClprMaintenanceTransactionBody;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.impl.BlockStreamBuilder;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.state.WrappedWritableStates;
import com.hedera.node.app.workflows.handle.record.SystemTransactions;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Emits informational {@link ClprMaintenanceTransactionBody} block-stream transactions for CLPR work performed
 * outside transaction handling.
 */
public class ClprMaintenanceTransactions {
    private static final List<Integer> SINGLETON_IDS = List.of(
                    LEDGER_CONFIGURATION_STATE_ID, ENDPOINT_MANIFEST_STATE_ID, ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID)
            .stream()
            .sorted()
            .toList();

    private final ConfigProvider configProvider;
    private final BlockStreamManager blockStreamManager;
    private final SystemTransactions systemTransactions;
    private final ExchangeRateManager exchangeRateManager;

    public ClprMaintenanceTransactions(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockStreamManager blockStreamManager,
            @NonNull final SystemTransactions systemTransactions,
            @NonNull final ExchangeRateManager exchangeRateManager) {
        this.configProvider = Objects.requireNonNull(configProvider);
        this.blockStreamManager = Objects.requireNonNull(blockStreamManager);
        this.systemTransactions = Objects.requireNonNull(systemTransactions);
        this.exchangeRateManager = Objects.requireNonNull(exchangeRateManager);
    }

    /**
     * Stages singleton maintenance, then commits and emits it only if values actually change.
     * The emitted body identifies the reason for the maintenance; its associated state changes are authoritative.
     * It is not submitted to a handler, charged fees, or given a child transaction.
     *
     * @param states CLPR writable states
     * @param before the first event or user transaction that must follow this maintenance
     * @param action maintenance restricted to the three CLPR singletons
     * @param reason the reason for the maintenance
     */
    public void perform(
            @NonNull final WritableStates states,
            @NonNull final Instant before,
            @NonNull final Consumer<WritableStates> action,
            @NonNull final ClprMaintenanceReason reason) {
        final var staged = new WrappedWritableStates(states);
        action.accept(staged);
        final var changes = new ArrayList<StateChange>();
        for (final int id : SINGLETON_IDS) {
            final var previous = states.getSingleton(id).get();
            final var updated = staged.getSingleton(id).get();
            if (!Objects.equals(previous, updated)) {
                changes.add(StateChange.newBuilder()
                        .stateId(id)
                        .singletonUpdate(new SingletonUpdateChange(
                                false,
                                List.of(),
                                singletonUpdateChangeValueFor(Objects.requireNonNull(
                                        updated, "CLPR singleton removal must use its empty value"))))
                        .build());
            }
        }
        if (changes.isEmpty()) {
            return;
        }
        if (configProvider
                        .getConfiguration()
                        .getConfigData(BlockStreamConfig.class)
                        .streamMode()
                == RECORDS) {
            staged.commit();
            return;
        }
        // Reserve time and construct output before mutating consensus state.
        final var consensusTime = systemTransactions.nextReservedSystemTimeFor(before);
        final var transactionId = systemTransactions.nextSystemTransactionIdAt(consensusTime);
        final var body = TransactionBody.newBuilder()
                .transactionID(transactionId)
                .clprMaintenance(ClprMaintenanceTransactionBody.newBuilder()
                        .reason(reason)
                        .build())
                .build();
        // Built by hand rather than dispatched, since the state changes are staged and diffed before the transaction
        // exists, and are not the effects of any handler
        final var builder = new BlockStreamBuilder(IRREVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, NODE);
        builder.functionality(CLPR_MAINTENANCE)
                .signedTx(SignedTransaction.newBuilder()
                        .bodyBytes(TransactionBody.PROTOBUF.toBytes(body))
                        .build())
                .transactionID(transactionId)
                .exchangeRate(exchangeRateManager.exchangeRates())
                .status(SUCCESS)
                .consensusTimestamp(consensusTime)
                .stateChanges(changes);
        final var output = builder.build(true, null);
        staged.commit();
        blockStreamManager.writeSavepointItems(output.blockItems(), consensusTime);
    }
}
