// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_BYTECODE_EMPTY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ERROR_DECODING_BYTESTRING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FILE_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.hapi.utils.EntityType.ACCOUNT;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.bloomForAll;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.removeIfAnyLeading0x;
import static com.hedera.services.bdd.junit.support.translators.BaseTranslator.mapTracesToVerboseLogs;
import static com.hedera.services.bdd.junit.support.translators.BaseTranslator.resultBuilderFrom;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.trace.ExecutedInitcode;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Translates a contract create transaction into a {@link SingleTransactionRecord}.
 */
public class ContractCreateTranslator implements BlockTransactionPartsTranslator {
    private static final Logger log = LogManager.getLogger(ContractCreateTranslator.class);

    private static final Set<String> TESTS_WITH_DISABLED_BYTECODE_SIDECARS =
            Set.of("TraceabilitySuite.actionsShowPropagatedRevert");
    private static final Set<ResponseCodeEnum> SKIPPED_INITCODE_STATUSES =
            Set.of(ERROR_DECODING_BYTESTRING, CONTRACT_BYTECODE_EMPTY, FILE_DELETED);

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
                    parts.outputIfPresent(TransactionOutput.TransactionOneOfType.CONTRACT_CREATE)
                            .map(TransactionOutput::contractCreateOrThrow)
                            .ifPresent(createContractOutput -> {
                                final var derivedBuilder =
                                        resultBuilderFrom(createContractOutput.evmTransactionResultOrThrow());
                                ContractID createdId = null;
                                if (parts.status() == SUCCESS) {
                                    if (parts.isTopLevel() || parts.isInnerBatchTxn()) {
                                        // If all sidecars are disabled and there were no logs for a top-level creation,
                                        // for parity we still need to fill in the result with empty logs and implied
                                        // bloom
                                        if (!parts.hasTraces()
                                                && parts.transactionIdOrThrow().nonce() == 0) {
                                            derivedBuilder
                                                    .logInfo(List.of())
                                                    .bloom(bloomForAll(List.of()))
                                                    .build();
                                        } else {
                                            mapTracesToVerboseLogs(derivedBuilder, parts.traces());
                                        }
                                        baseTranslator.addCreatedIdsTo(derivedBuilder, remainingStateChanges);
                                        baseTranslator.addChangedContractNonces(derivedBuilder, remainingStateChanges);
                                    }
                                    createdId = createContractOutput
                                            .evmTransactionResultOrThrow()
                                            .contractIdOrThrow();
                                    baseTranslator.addCreatedEvmAddressTo(
                                            derivedBuilder, createdId, remainingStateChanges);
                                }
                                if (!SKIPPED_INITCODE_STATUSES.contains(parts.status())) {
                                    Bytes initcode = null;
                                    boolean needsExternalization = false;
                                    final var op = parts.body().contractCreateInstanceOrThrow();
                                    if (op.hasInitcode()) {
                                        initcode = op.initcodeOrThrow();
                                    } else if (!TESTS_WITH_DISABLED_BYTECODE_SIDECARS.contains(
                                            parts.body().memo())) {
                                        final long fileNum =
                                                op.fileIDOrElse(FileID.DEFAULT).fileNum();
                                        if (baseTranslator.knowsFileContents(fileNum)) {
                                            initcode = baseTranslator.getFileContents(fileNum);
                                            final var hexedInitcode = new String(removeIfAnyLeading0x(initcode));
                                            initcode = Bytes.fromHex(hexedInitcode
                                                    + op.constructorParameters().toHex());
                                            needsExternalization = true;
                                        }
                                    }
                                    if (initcode != null) {
                                        final var builder = ExecutedInitcode.newBuilder();
                                        if (createdId != null) {
                                            builder.contractId(createdId);
                                        }
                                        if (needsExternalization) {
                                            builder.explicitInitcode(initcode);
                                        }
                                        baseTranslator.trackInitcode(
                                                parts.consensusTimestamp(), builder.build(), false);
                                    }
                                }
                                recordBuilder.contractCreateResult(derivedBuilder.build());
                            });
                    if (parts.status() == SUCCESS) {
                        final var output = parts.createContractOutputOrThrow();
                        final var contractNum = output.evmTransactionResultOrThrow()
                                .contractIdOrThrow()
                                .contractNumOrThrow();
                        if (baseTranslator.entityCreatedThisUnit(contractNum)) {
                            long createdNum = baseTranslator.nextCreatedNum(ACCOUNT);
                            if (contractNum != createdNum) {
                                if (createdNum > 1000) {
                                    log.error(
                                            "Expected {} to be the next created contract, but got {}",
                                            contractNum,
                                            createdNum);
                                } else {
                                    // Override weird BlockUnitSplit behavior at genesis
                                    createdNum = contractNum;
                                }
                            }
                            final var iter = remainingStateChanges.listIterator();
                            while (iter.hasNext()) {
                                final var stateChange = iter.next();
                                if (stateChange.hasMapUpdate()
                                        && stateChange
                                                .mapUpdateOrThrow()
                                                .keyOrThrow()
                                                .hasAccountIdKey()) {
                                    final var accountId = stateChange
                                            .mapUpdateOrThrow()
                                            .keyOrThrow()
                                            .accountIdKeyOrThrow();
                                    if (accountId.accountNumOrThrow() == createdNum) {
                                        receiptBuilder.contractID(ContractID.newBuilder()
                                                .shardNum(accountId.shardNum())
                                                .realmNum(accountId.realmNum())
                                                .contractNum(createdNum)
                                                .build());
                                        iter.remove();
                                        return;
                                    }
                                }
                            }
                        }
                        // If we reach here, we didn't find the created contract in the remaining state changes
                        // so it must have been an existing hollow account finalized as a contract
                        final var op = parts.body().contractCreateInstanceOrThrow();
                        final var selfAdminId = op.adminKeyOrThrow().contractIDOrThrow();
                        receiptBuilder.contractID(selfAdminId);
                    }
                },
                remainingStateChanges,
                followingUnitTraces);
    }
}
