// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.services.bdd.junit.support.translators.BaseTranslator.mapTracesToVerboseLogs;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Translates a ethereum transaction into a {@link SingleTransactionRecord}.
 */
public class EthereumTransactionTranslator implements BlockTransactionPartsTranslator {
    @Override
    public SingleTransactionRecord translate(
            @NonNull final BlockTransactionParts parts,
            @NonNull final BaseTranslator baseTranslator,
            @NonNull final List<StateChange> remainingStateChanges,
            @NonNull final List<TraceData> followingUnitTraces) {
        requireNonNull(parts);
        requireNonNull(baseTranslator);
        requireNonNull(remainingStateChanges);
        return baseTranslator.recordFrom(
                parts,
                (receiptBuilder, recordBuilder) -> {
                    parts.outputIfPresent(TransactionOutput.TransactionOneOfType.ETHEREUM_CALL)
                            .map(TransactionOutput::ethereumCallOrThrow)
                            .ifPresent(ethTxOutput -> {
                                recordBuilder.ethereumHash(ethTxOutput.ethereumHash());
                                final var result =
                                        switch (ethTxOutput.ethResult().kind()) {
                                            // CONSENSUS_GAS_EXHAUSTED
                                            case UNSET -> ContractFunctionResult.DEFAULT;
                                            case ETHEREUM_CALL_RESULT -> {
                                                var resultBuilder = ethTxOutput
                                                        .ethereumCallResultOrThrow()
                                                        .copyBuilder();
                                                if (parts.status() == SUCCESS) {
                                                    mapTracesToVerboseLogs(resultBuilder, parts.traces());
                                                }
                                                recordBuilder.contractCallResult(resultBuilder);
                                                yield resultBuilder.build();
                                            }
                                            case ETHEREUM_CREATE_RESULT -> {
                                                var resultBuilder = ethTxOutput
                                                        .ethereumCreateResultOrThrow()
                                                        .copyBuilder();
                                                if (parts.status() == SUCCESS) {
                                                    mapTracesToVerboseLogs(resultBuilder, parts.traces());
                                                }
                                                recordBuilder.contractCreateResult(resultBuilder);
                                                yield resultBuilder.build();
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
