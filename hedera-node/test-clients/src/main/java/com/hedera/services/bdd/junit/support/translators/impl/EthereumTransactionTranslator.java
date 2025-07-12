// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.bloomForAll;
import static com.hedera.services.bdd.junit.support.translators.BaseTranslator.mapTracesToVerboseLogs;
import static com.hedera.services.bdd.junit.support.translators.BaseTranslator.resultBuilderFrom;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Translates a ethereum transaction into a {@link SingleTransactionRecord}.
 */
public class EthereumTransactionTranslator implements BlockTransactionPartsTranslator {
    private static final String PRE_NONCE_ERROR_MESSAGE = "0x5452414e53414354494f4e5f4f56455253495a45";

    @Override
    public SingleTransactionRecord translate(
            @NonNull final BlockTransactionParts parts,
            @NonNull final BaseTranslator baseTranslator,
            @NonNull final List<StateChange> remainingStateChanges,
            @Nullable final List<TraceData> tracesSoFar,
            @NonNull final List<TraceData> followingUnitTraces) {
        requireNonNull(parts);
        requireNonNull(baseTranslator);
        requireNonNull(remainingStateChanges);
        return baseTranslator.recordFrom(
                parts,
                (receiptBuilder, recordBuilder) -> {
                    final var ethTxData = EthTxData.populateEthTxData(parts.body()
                            .ethereumTransactionOrThrow()
                            .ethereumData()
                            .toByteArray());
                    parts.outputIfPresent(TransactionOutput.TransactionOneOfType.ETHEREUM_CALL)
                            .map(TransactionOutput::ethereumCallOrThrow)
                            .ifPresent(ethTxOutput -> {
                                if (ethTxOutput.ethereumHash().length() > 0) {
                                    recordBuilder.ethereumHash(ethTxOutput.ethereumHash());
                                } else {
                                    recordBuilder.ethereumHash(
                                            Bytes.wrap(requireNonNull(ethTxData).getEthereumHash()));
                                }
                                final var result =
                                        switch (ethTxOutput.txnResult().kind()) {
                                            // CONSENSUS_GAS_EXHAUSTED
                                            case UNSET -> ContractFunctionResult.DEFAULT;
                                            case EVM_CALL_TRANSACTION_RESULT -> {
                                                final var txCallResult = ethTxOutput.evmCallTransactionResultOrThrow();
                                                final var derivedBuilder = resultBuilderFrom(txCallResult);
                                                if (ethTxData != null) {
                                                    derivedBuilder.gas(ethTxData.gasLimit());
                                                    derivedBuilder.amount(ethTxData.getAmount());
                                                    if (txCallResult.hasInternalCallContext()) {
                                                        derivedBuilder.functionParameters(txCallResult
                                                                .internalCallContextOrThrow()
                                                                .callData());
                                                    } else {
                                                        derivedBuilder.functionParameters(
                                                                Bytes.wrap(ethTxData.callData()));
                                                    }
                                                }
                                                if (parts.status() == SUCCESS
                                                        && (parts.isTopLevel() || parts.inBatch())) {
                                                    mapTracesToVerboseLogs(derivedBuilder, parts.traces());
                                                    baseTranslator.addCreatedIdsTo(
                                                            derivedBuilder, remainingStateChanges);
                                                    baseTranslator.addChangedContractNonces(
                                                            derivedBuilder, remainingStateChanges);
                                                }
                                                if (!PRE_NONCE_ERROR_MESSAGE.equals(txCallResult.errorMessage())) {
                                                    baseTranslator.addSignerNonce(
                                                            txCallResult.senderId(),
                                                            derivedBuilder,
                                                            remainingStateChanges);
                                                }
                                                final var fnResult = derivedBuilder.build();
                                                recordBuilder.contractCallResult(fnResult);
                                                yield fnResult;
                                            }
                                            case EVM_CREATE_TRANSACTION_RESULT -> {
                                                final var txCreateResult =
                                                        ethTxOutput.evmCreateTransactionResultOrThrow();
                                                final var derivedBuilder = resultBuilderFrom(txCreateResult);
                                                if (ethTxData != null) {
                                                    derivedBuilder.gas(ethTxData.gasLimit());
                                                    derivedBuilder.amount(ethTxData.getAmount());
                                                    if (txCreateResult.hasInternalCallContext()) {
                                                        derivedBuilder.functionParameters(txCreateResult
                                                                .internalCallContextOrThrow()
                                                                .callData());
                                                    } else {
                                                        derivedBuilder.functionParameters(
                                                                Bytes.wrap(ethTxData.callData()));
                                                    }
                                                }
                                                if (parts.status() == SUCCESS) {
                                                    if (parts.isTopLevel() || parts.inBatch()) {
                                                        // If all sidecars are disabled and there were no logs for a
                                                        // top-level creation,
                                                        // for parity we still need to fill in the result with empty
                                                        // logs and implied bloom
                                                        if (!parts.hasTraces()
                                                                && parts.transactionIdOrThrow()
                                                                                .nonce()
                                                                        == 0) {
                                                            derivedBuilder
                                                                    .logInfo(List.of())
                                                                    .bloom(bloomForAll(List.of()))
                                                                    .build();
                                                        } else {
                                                            mapTracesToVerboseLogs(derivedBuilder, parts.traces());
                                                        }
                                                        baseTranslator.addCreatedIdsTo(
                                                                derivedBuilder, remainingStateChanges);
                                                        baseTranslator.addChangedContractNonces(
                                                                derivedBuilder, remainingStateChanges);
                                                    }
                                                    final var createdId = txCreateResult.contractIdOrThrow();
                                                    baseTranslator.addCreatedEvmAddressTo(
                                                            derivedBuilder, createdId, remainingStateChanges);
                                                }
                                                if (!PRE_NONCE_ERROR_MESSAGE.equals(txCreateResult.errorMessage())) {
                                                    baseTranslator.addSignerNonce(
                                                            txCreateResult.senderId(),
                                                            derivedBuilder,
                                                            remainingStateChanges);
                                                }
                                                final var fnResult = derivedBuilder.build();
                                                recordBuilder.contractCreateResult(fnResult);
                                                yield fnResult;
                                            }
                                        };
                                if (result.gasUsed() > 0L) {
                                    receiptBuilder.contractID(result.contractID());
                                }
                            });
                },
                remainingStateChanges,
                followingUnitTraces);
    }
}
