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
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.contract.EvmTransactionResult;
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
import java.util.Map;

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
                            final var clprDispatch = isClprDispatch(dispatchedChild, evmResult);
                            final var isDispatched = isHook || clprDispatch;
                            if (parts.status() == SUCCESS
                                    && (isDispatched || parts.isTopLevel() || parts.isInnerBatchTxn())) {
                                if (needsClippedTraceBloomFallback(parts)) {
                                    // This fixture clips the only bloom-implying trace data, leaving implied bloom.
                                    derivedBuilder.logInfo(List.of()).bloom(bloomForAll(List.of()));
                                } else {
                                    mapTracesToVerboseLogs(derivedBuilder, parts.traces());
                                }
                                if (clprDispatch) {
                                    // Several calls dispatched in one unit: claim only this call's creations
                                    baseTranslator.addCreatedIdsOfCallTo(derivedBuilder, parts, remainingStateChanges);
                                } else if (executingHookId == null) {
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
                            final var reachedTarget = !clprDispatch
                                    || reached(
                                            parts.body().contractCallOrThrow().contractIDOrThrow(),
                                            result.contractIDOrElse(ContractID.DEFAULT),
                                            baseTranslator.getAliases());
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
     * Whether a child contract call in a unit with a CLPR transaction is a call the CLPR transaction dispatched as its
     * own child transaction. A system contract called by such a call (e.g. a CLPR application callback calling HTS
     * {@code isToken()}) also produces a child {@code CONTRACT_CALL}, but its result always carries the calling
     * contract as sender, while a dispatched call's never does (only Ethereum transactions set it).
     *
     * @param dispatchedChild whether the call is a child contract call in a unit with a CLPR transaction
     * @param evmResult the call's EVM result
     * @return whether the call was dispatched by the CLPR transaction
     */
    static boolean isClprDispatch(final boolean dispatchedChild, @NonNull final EvmTransactionResult evmResult) {
        return dispatchedChild && !evmResult.hasSenderId();
    }

    /**
     * Whether a call to the given target reached the given contract, comparing by number when the target
     * has one, or otherwise by the EVM address of the target, which may be either an alias of the contract
     * (e.g. a CLPR callback to a CREATE2 application) or its long-zero address.
     *
     * @param target the contract the call was addressed to
     * @param called the contract the call's result reports
     * @param aliases the EVM address aliases known so far, by alias
     * @return whether the call reached its target
     */
    static boolean reached(
            @NonNull final ContractID target,
            @NonNull final ContractID called,
            @NonNull final Map<Bytes, AccountID> aliases) {
        final long calledNum = called.contractNumOrElse(0L);
        if (target.hasContractNum()) {
            return target.contractNumOrThrow() == calledNum;
        }
        if (!target.hasEvmAddress()) {
            return false;
        }
        final var address = target.evmAddressOrThrow();
        final var aliased = aliases.get(address);
        if (aliased != null) {
            return aliased.accountNumOrElse(0L) == calledNum;
        }
        return address.equals(Bytes.wrap(asEvmAddress(calledNum)));
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
