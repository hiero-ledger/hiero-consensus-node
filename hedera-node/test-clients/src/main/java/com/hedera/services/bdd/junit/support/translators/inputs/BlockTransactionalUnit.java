// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.inputs;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.services.bdd.junit.support.translators.ScopedTraceData;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A grouping of block stream information used as input to record translation, where all the information is
 * linked to the same {@link TransactionID} and hence is part of the same transactional unit.
 * <p>
 * May include multiple logical HAPI transactions, and the state changes they produce.
 */
public record BlockTransactionalUnit(
        @NonNull List<BlockTransactionParts> blockTransactionParts, @NonNull List<StateChange> stateChanges) {
    /**
     * Returns all (possibly hook-scoped) trace data in this unit.
     */
    public List<ScopedTraceData> allScopedTraces() {
        final List<ScopedTraceData> scopedTraces = new ArrayList<>();
        final List<HookId> execHookIds = allHookExecIds();
        for (final var parts : blockTransactionParts) {
            if (!parts.hasTraces()) {
                continue;
            }
            if (execHookIds == null || !parts.isHookCall()) {
                for (final var trace : parts.tracesOrThrow()) {
                    scopedTraces.add(new ScopedTraceData(trace, null));
                }
            } else {
                final var hookId = parts.isHookCall() ? execHookIds.removeFirst() : null;
                for (final var trace : parts.tracesOrThrow()) {
                    scopedTraces.add(new ScopedTraceData(trace, hookId));
                }
            }
        }
        return scopedTraces;
    }

    /**
     * If applicable to this unit, returns the hook execution ids for all hook executions in the unit.
     */
    public @Nullable List<HookId> allHookExecIds() {
        CryptoTransferTransactionBody op = null;
        int numHookDispatches = 0;
        for (final var parts : blockTransactionParts) {
            if (parts.isTopLevel()) {
                if (parts.functionality() == CRYPTO_TRANSFER) {
                    op = parts.body().cryptoTransferOrThrow();
                } else {
                    break;
                }
            } else {
                if (parts.functionality() == CONTRACT_CALL) {
                    numHookDispatches++;
                }
            }
        }
        if (op != null && numHookDispatches > 0) {
            final List<HookId> allowExecHookIds = new ArrayList<>(numHookDispatches);
            final List<HookId> allowPreExecHookIds = new ArrayList<>(numHookDispatches);
            final List<HookId> allowPostExecHookIds = new ArrayList<>(numHookDispatches);
            for (final var aa : op.transfersOrElse(TransferList.DEFAULT).accountAmounts()) {
                if (aa.hasPreTxAllowanceHook()) {
                    allowExecHookIds.add(HookId.newBuilder()
                            .entityId(HookEntityId.newBuilder().accountId(aa.accountIDOrThrow()))
                            .hookId(aa.preTxAllowanceHookOrThrow().hookIdOrThrow())
                            .build());
                } else if (aa.hasPrePostTxAllowanceHook()) {
                    final var hookId = HookId.newBuilder()
                            .entityId(HookEntityId.newBuilder().accountId(aa.accountIDOrThrow()))
                            .hookId(aa.prePostTxAllowanceHookOrThrow().hookIdOrThrow())
                            .build();
                    allowPreExecHookIds.add(hookId);
                    allowPostExecHookIds.add(hookId);
                }
            }
            for (final var ttl : op.tokenTransfers()) {
                for (final var aa : ttl.transfers()) {
                    if (aa.hasPreTxAllowanceHook()) {
                        allowExecHookIds.add(HookId.newBuilder()
                                .entityId(HookEntityId.newBuilder().accountId(aa.accountIDOrThrow()))
                                .hookId(aa.preTxAllowanceHookOrThrow().hookIdOrThrow())
                                .build());
                    } else if (aa.hasPrePostTxAllowanceHook()) {
                        final var hookId = HookId.newBuilder()
                                .entityId(HookEntityId.newBuilder().accountId(aa.accountIDOrThrow()))
                                .hookId(aa.prePostTxAllowanceHookOrThrow().hookIdOrThrow())
                                .build();
                        allowPreExecHookIds.add(hookId);
                        allowPostExecHookIds.add(hookId);
                    }
                }
                for (final NftTransfer nft : ttl.nftTransfers()) {
                    if (nft.hasPreTxSenderAllowanceHook()) {
                        allowExecHookIds.add(HookId.newBuilder()
                                .entityId(HookEntityId.newBuilder().accountId(nft.senderAccountIDOrThrow()))
                                .hookId(nft.preTxSenderAllowanceHookOrThrow().hookIdOrThrow())
                                .build());
                    } else if (nft.hasPrePostTxSenderAllowanceHook()) {
                        final var hookId = HookId.newBuilder()
                                .entityId(HookEntityId.newBuilder().accountId(nft.senderAccountIDOrThrow()))
                                .hookId(nft.prePostTxSenderAllowanceHookOrThrow()
                                        .hookIdOrThrow())
                                .build();
                        allowPreExecHookIds.add(hookId);
                        allowPostExecHookIds.add(hookId);
                    }
                    if (nft.hasPreTxReceiverAllowanceHook()) {
                        allowExecHookIds.add(HookId.newBuilder()
                                .entityId(HookEntityId.newBuilder().accountId(nft.receiverAccountIDOrThrow()))
                                .hookId(nft.preTxReceiverAllowanceHookOrThrow().hookIdOrThrow())
                                .build());
                    } else if (nft.hasPrePostTxReceiverAllowanceHook()) {
                        final var hookId = HookId.newBuilder()
                                .entityId(HookEntityId.newBuilder().accountId(nft.receiverAccountIDOrThrow()))
                                .hookId(nft.prePostTxReceiverAllowanceHookOrThrow()
                                        .hookIdOrThrow())
                                .build();
                        allowPreExecHookIds.add(hookId);
                        allowPostExecHookIds.add(hookId);
                    }
                }
            }
            final List<HookId> execHookIds = new ArrayList<>(numHookDispatches);
            execHookIds.addAll(allowExecHookIds);
            execHookIds.addAll(allowPreExecHookIds);
            execHookIds.addAll(allowPostExecHookIds);
            return execHookIds.subList(0, numHookDispatches);
        } else {
            return null;
        }
    }

    /**
     * Returns the unit with the inner transactions of any atomic batch transaction parts replaced with their
     * respective inner transactions.
     * @return the unit with inner transactions replaced with their respective inner transactions
     */
    public BlockTransactionalUnit withBatchTransactionParts() {
        boolean anyUnitMissing = false;
        for (final var parts : blockTransactionParts) {
            if (parts.transactionParts() == null) {
                anyUnitMissing = true;
                break;
            }
        }
        // If no unit is missing, then we can return the original unit. This means there are no batch transactions
        if (!anyUnitMissing) {
            return this;
        }
        // find atomic batch transaction parts
        final var batchParts = blockTransactionParts.stream()
                .filter(parts -> parts.functionality() == HederaFunctionality.ATOMIC_BATCH)
                .findFirst()
                .orElseThrow();
        // get queue of inner transactions from the atomic batch parts
        final var innerTxns = new ArrayDeque<>(batchParts.body().atomicBatchOrThrow().transactions().stream()
                .map(TransactionParts::from)
                .toList());
        // Insert the inner transactions into the block transaction parts. Once we insert them, we can
        //  do the rest of the logic like usual
        for (int i = 0; i < blockTransactionParts.size(); i++) {
            final var parts = blockTransactionParts.get(i);
            if (parts.transactionParts() == null) {
                // replace it with inner transaction
                blockTransactionParts.set(i, parts.withPartsFromBatchParent(innerTxns.removeFirst()));
            }
        }
        return this;
    }
}
