// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_FILE_EMPTY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FILE_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.bloomForAll;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.removeIfAnyLeading0x;
import static com.hedera.services.bdd.junit.support.translators.BaseTranslator.mapTracesToVerboseLogs;
import static com.hedera.services.bdd.junit.support.translators.BaseTranslator.resultBuilderFrom;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bouncycastle.util.encoders.Hex;

/**
 * Translates a ethereum transaction into a {@link SingleTransactionRecord}.
 */
public class EthereumTransactionTranslator implements BlockTransactionPartsTranslator {
    private static final String PRE_NONCE_ERROR_MESSAGE = "0x5452414e53414354494f4e5f4f56455253495a45";

    private static final Set<ResponseCodeEnum> HYDRATION_FAILURE_CODES =
            EnumSet.of(INVALID_FILE_ID, FILE_DELETED, CONTRACT_FILE_EMPTY);

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
                    final var ethTx = parts.body().ethereumTransactionOrThrow();
                    var ethTxData =
                            EthTxData.populateEthTxData(ethTx.ethereumData().toByteArray());
                    // Consensus node only tries to hydrate if the call data is not already present
                    if (ethTxData != null
                            && !ethTxData.hasCallData()
                            && !HYDRATION_FAILURE_CODES.contains(parts.status())
                            && ethTx.hasCallData()) {
                        final var callDataFileNum = ethTx.callDataOrThrow().fileNum();
                        final var hexedCallData = baseTranslator.getFileContents(callDataFileNum);
                        final var callData = Hex.decode(removeIfAnyLeading0x(hexedCallData));
                        ethTxData = ethTxData.replaceCallData(callData);
                    }
                    final var finalEthTxData = ethTxData;
                    parts.outputIfPresent(TransactionOutput.TransactionOneOfType.ETHEREUM_CALL)
                            .map(TransactionOutput::ethereumCallOrThrow)
                            .ifPresent(ethTxOutput -> {
                                if (finalEthTxData != null) {
                                    recordBuilder.ethereumHash(Bytes.wrap(
                                            requireNonNull(finalEthTxData).getEthereumHash()));
                                }
                                final var result =
                                        switch (ethTxOutput.transactionResult().kind()) {
                                            // CONSENSUS_GAS_EXHAUSTED
                                            case UNSET -> ContractFunctionResult.DEFAULT;
                                            case EVM_CALL_TRANSACTION_RESULT -> {
                                                final var txCallResult = ethTxOutput.evmCallTransactionResultOrThrow();
                                                final var derivedBuilder = resultBuilderFrom(txCallResult);
                                                if (finalEthTxData != null) {
                                                    derivedBuilder.gas(finalEthTxData.gasLimit());
                                                    derivedBuilder.amount(finalEthTxData.getAmount());
                                                    derivedBuilder.functionParameters(
                                                            Bytes.wrap(finalEthTxData.callData()));
                                                }
                                                if (parts.status() == SUCCESS
                                                        && (parts.isTopLevel() || parts.isInnerBatchTxn())) {
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
                                                if (finalEthTxData != null) {
                                                    derivedBuilder.gas(finalEthTxData.gasLimit());
                                                    derivedBuilder.amount(finalEthTxData.getAmount());
                                                    derivedBuilder.functionParameters(
                                                            Bytes.wrap(finalEthTxData.callData()));
                                                }
                                                if (parts.status() == SUCCESS) {
                                                    if (parts.isTopLevel() || parts.isInnerBatchTxn()) {
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
