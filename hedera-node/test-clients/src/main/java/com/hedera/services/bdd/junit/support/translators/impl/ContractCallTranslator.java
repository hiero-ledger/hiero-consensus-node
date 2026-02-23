// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.services.bdd.junit.support.translators.BaseTranslator.mapTracesToVerboseLogs;
import static com.hedera.services.bdd.junit.support.translators.BaseTranslator.resultBuilderFrom;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.HookId;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.ScopedTraceData;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import com.hedera.services.bdd.junit.support.translators.inputs.HookMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Translates a contract call transaction into a {@link SingleTransactionRecord}.
 */
public class ContractCallTranslator implements BlockTransactionPartsTranslator {
    @Override
    public SingleTransactionRecord translate(
            @NonNull final BlockTransactionParts parts,
            @NonNull final BaseTranslator baseTranslator,
            @NonNull final List<StateChange> remainingStateChanges,
            @Nullable final List<TraceData> tracesSoFar,
            @NonNull final List<ScopedTraceData> followingUnitTraces,
            @Nullable final HookId executingHookId,
            @Nullable final HookMetadata hookMetadata) {
        return baseTranslator.recordFrom(
                parts,
                (receiptBuilder, recordBuilder) -> parts.outputIfPresent(
                                TransactionOutput.TransactionOneOfType.CONTRACT_CALL)
                        .map(TransactionOutput::contractCallOrThrow)
                        .ifPresent(callContractOutput -> {
                            final var evmResult = callContractOutput.evmTransactionResultOrThrow();
                            final var derivedBuilder = resultBuilderFrom(evmResult);
                            final var contractId = evmResult.contractId();
                            final var isHook = contractId != null && contractId.contractNumOrElse(0L) == 365;
                            if (parts.status() == SUCCESS
                                    && (isHook || parts.isTopLevel() || parts.isInnerBatchTxn())) {
                                mapTracesToVerboseLogs(derivedBuilder, parts.traces());
                                if (executingHookId == null) {
                                    baseTranslator.addCreatedIdsTo(derivedBuilder, remainingStateChanges);
                                } else {
                                    final var hookCreations = requireNonNull(hookMetadata)
                                            .hookCreations()
                                            .get(executingHookId);
                                    if (hookCreations != null && !hookCreations.isEmpty()) {
                                        derivedBuilder.createdContractIDs(hookCreations.removeFirst());
                                    }
                                }
                                baseTranslator.addChangedContractNonces(derivedBuilder, evmResult.contractNonces());
                            }
                            final var result = derivedBuilder.build();
                            recordBuilder.contractCallResult(result);
                            if ((parts.transactionIdOrThrow().nonce() == 0 || isHook) && result.gasUsed() > 0L) {
                                receiptBuilder.contractID(result.contractID());
                            }
                        }),
                remainingStateChanges,
                followingUnitTraces,
                executingHookId);
    }
}
