// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.bloomForAll;
import static com.hedera.services.bdd.junit.support.translators.BaseTranslator.mapTracesToVerboseLogs;
import static com.hedera.services.bdd.junit.support.translators.BaseTranslator.resultBuilderFrom;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HookId;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.ScopedTraceData;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import com.hedera.services.bdd.junit.support.translators.inputs.HookMetadata;
import com.hedera.services.bdd.suites.contract.records.RecordsSuite;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Translates a contract call transaction into a {@link SingleTransactionRecord}.
 */
public class ContractCallTranslator implements BlockTransactionPartsTranslator {
    /**
     * Whether the calls translated are dispatched as their own child transactions (e.g. by a CLPR
     * transaction), so their records carry the same receipt and result fields as a top-level call.
     */
    private final boolean dispatchedChild;

    public ContractCallTranslator() {
        this(false);
    }

    public ContractCallTranslator(final boolean dispatchedChild) {
        this.dispatchedChild = dispatchedChild;
    }

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
                            final var isDispatched = isHook || dispatchedChild;
                            if (parts.status() == SUCCESS
                                    && (isDispatched || parts.isTopLevel() || parts.isInnerBatchTxn())) {
                                if (needsClippedTraceBloomFallback(parts)) {
                                    // This fixture clips the only bloom-implying trace data, leaving implied bloom.
                                    derivedBuilder.logInfo(List.of()).bloom(bloomForAll(List.of()));
                                } else {
                                    mapTracesToVerboseLogs(derivedBuilder, parts.traces());
                                }
                                if (executingHookId == null) {
                                    baseTranslator.addCreatedIdsTo(derivedBuilder, parts, remainingStateChanges);
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
                            // The node sets the receipt's contract id to the call's recipient, which is unknown
                            // when a dispatched call never reached its target (e.g. a CLPR verifier call with too
                            // little gas reports another contract in its result)
                            final var reachedTarget = !dispatchedChild
                                    || reached(
                                            parts.body().contractCallOrThrow().contractIDOrThrow(),
                                            result.contractIDOrElse(ContractID.DEFAULT));
                            if ((parts.transactionIdOrThrow().nonce() == 0 || isDispatched)
                                    && result.gasUsed() > 0L
                                    && reachedTarget) {
                                receiptBuilder.contractID(result.contractID());
                            }
                        }),
                remainingStateChanges,
                followingUnitTraces,
                executingHookId);
    }

    /**
     * Whether a call to the given target reached the given contract, comparing by number when the target
     * has one, or by the long-zero EVM address of the contract otherwise.
     */
    private static boolean reached(@NonNull final ContractID target, @NonNull final ContractID called) {
        final long calledNum = called.contractNumOrElse(0L);
        if (target.hasContractNum()) {
            return target.contractNumOrThrow() == calledNum;
        }
        return target.hasEvmAddress() && target.evmAddressOrThrow().equals(Bytes.wrap(asEvmAddress(calledNum)));
    }

    private static boolean needsClippedTraceBloomFallback(@NonNull final BlockTransactionParts parts) {
        return RecordsSuite.OVERSIZED_CONTRACT_ACTIONS_MEMO.equals(parts.memo())
                && (parts.traces() == null || parts.traces().stream().noneMatch(ContractCallTranslator::impliesBloom));
    }

    private static boolean impliesBloom(@NonNull final TraceData trace) {
        if (!trace.hasEvmTraceData()) {
            return false;
        }
        final var evmTraceData = trace.evmTraceDataOrThrow();
        return !evmTraceData.logs().isEmpty()
                || !evmTraceData.contractSlotUsages().isEmpty()
                || !evmTraceData.contractActions().isEmpty();
    }
}
