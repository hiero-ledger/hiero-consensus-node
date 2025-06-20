// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asBesuLog;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.bloomFor;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.bloomForAll;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.block.stream.trace.EvmTransactionLog;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.ContractLoginfo;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.blocks.impl.TranslationContext;
import com.hedera.node.app.blocks.impl.contexts.AirdropOpContext;
import com.hedera.node.app.blocks.impl.contexts.ContractOpContext;
import com.hedera.node.app.blocks.impl.contexts.CryptoOpContext;
import com.hedera.node.app.blocks.impl.contexts.FileOpContext;
import com.hedera.node.app.blocks.impl.contexts.MintOpContext;
import com.hedera.node.app.blocks.impl.contexts.NodeOpContext;
import com.hedera.node.app.blocks.impl.contexts.ScheduleOpContext;
import com.hedera.node.app.blocks.impl.contexts.SubmitOpContext;
import com.hedera.node.app.blocks.impl.contexts.SupplyChangeOpContext;
import com.hedera.node.app.blocks.impl.contexts.TokenOpContext;
import com.hedera.node.app.blocks.impl.contexts.TopicOpContext;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import org.hyperledger.besu.evm.log.Log;

/**
 * Translates a {@link TransactionResult} and, optionally, one or more {@link TransactionOutput}s within a given
 * {@link TranslationContext} into a {@link TransactionRecord} or {@link TransactionReceipt} appropriate for returning
 * from a query.
 */
public class BlockItemsTranslator {
    private static final Function<TransactionOutput, ContractFunctionResult> CONTRACT_CALL_EXTRACTOR =
            output -> output.contractCallOrThrow().contractCallResultOrThrow();
    private static final Function<TransactionOutput, ContractFunctionResult> CONTRACT_CREATE_EXTRACTOR =
            output -> output.contractCreateOrThrow().contractCreateResultOrThrow();

    public static final BlockItemsTranslator BLOCK_ITEMS_TRANSLATOR = new BlockItemsTranslator();

    /**
     * Translate the given {@link TransactionResult} and optional {@link TransactionOutput}s into a
     * {@link TransactionReceipt} appropriate for returning from a query.
     *
     * @param context the context of the transaction
     * @param result the result of the transaction
     * @param outputs the outputs of the transaction
     * @return the translated receipt
     */
    public TransactionReceipt translateReceipt(
            @NonNull final TranslationContext context,
            @NonNull final TransactionResult result,
            @NonNull final TransactionOutput... outputs) {
        requireNonNull(context);
        requireNonNull(result);
        requireNonNull(outputs);
        final var receiptBuilder = TransactionReceipt.newBuilder()
                .status(result.status())
                .exchangeRate(context.transactionExchangeRates());
        final var function = context.functionality();
        switch (function) {
            case CONTRACT_CALL, CONTRACT_CREATE, CONTRACT_DELETE, CONTRACT_UPDATE, ETHEREUM_TRANSACTION ->
                receiptBuilder.contractID(((ContractOpContext) context).contractId());
            case CRYPTO_CREATE, CRYPTO_UPDATE -> receiptBuilder.accountID(((CryptoOpContext) context).accountId());
            case FILE_CREATE -> receiptBuilder.fileID(((FileOpContext) context).fileId());
            case NODE_CREATE -> receiptBuilder.nodeId(((NodeOpContext) context).nodeId());
            case SCHEDULE_CREATE -> {
                final var scheduleOutput = outputValueIfPresent(
                        TransactionOutput::hasCreateSchedule, TransactionOutput::createScheduleOrThrow, outputs);
                if (scheduleOutput != null) {
                    receiptBuilder
                            .scheduleID(scheduleOutput.scheduleId())
                            .scheduledTransactionID(scheduleOutput.scheduledTransactionId());
                }
            }
            case SCHEDULE_DELETE -> receiptBuilder.scheduleID(((ScheduleOpContext) context).scheduleId());
            case SCHEDULE_SIGN -> {
                final var signOutput = outputValueIfPresent(
                        TransactionOutput::hasSignSchedule, TransactionOutput::signScheduleOrThrow, outputs);
                if (signOutput != null) {
                    receiptBuilder.scheduledTransactionID(signOutput.scheduledTransactionId());
                }
            }
            case CONSENSUS_SUBMIT_MESSAGE ->
                receiptBuilder
                        .topicRunningHashVersion(((SubmitOpContext) context).runningHashVersion())
                        .topicSequenceNumber(((SubmitOpContext) context).sequenceNumber())
                        .topicRunningHash(((SubmitOpContext) context).runningHash());
            case TOKEN_MINT ->
                receiptBuilder
                        .newTotalSupply(((MintOpContext) context).newTotalSupply())
                        .serialNumbers(((MintOpContext) context).serialNumbers());
            case TOKEN_BURN, TOKEN_ACCOUNT_WIPE ->
                receiptBuilder.newTotalSupply(((SupplyChangeOpContext) context).newTotalSupply());
            case TOKEN_CREATE -> receiptBuilder.tokenID(((TokenOpContext) context).tokenId());
            case CONSENSUS_CREATE_TOPIC -> receiptBuilder.topicID(((TopicOpContext) context).topicId());
        }
        return receiptBuilder.build();
    }

    /**
     * Translate the given {@link TransactionResult} and optional {@link TransactionOutput}s into a
     * {@link TransactionRecord} appropriate for returning from a query.
     *
     * @param context the context of the transaction
     * @param result the result of the transaction
     * @param logs the EVM logs of the transaction, if any
     * @param outputs the outputs of the transaction
     * @return the translated record
     */
    public TransactionRecord translateRecord(
            @NonNull final TranslationContext context,
            @NonNull final TransactionResult result,
            @Nullable final List<EvmTransactionLog> logs,
            @NonNull final TransactionOutput... outputs) {
        requireNonNull(context);
        requireNonNull(result);
        requireNonNull(outputs);
        final var recordBuilder = TransactionRecord.newBuilder()
                .transactionID(context.txnId())
                .memo(context.memo())
                .transactionHash(context.transactionHash())
                .consensusTimestamp(result.consensusTimestamp())
                .parentConsensusTimestamp(result.parentConsensusTimestamp())
                .scheduleRef(result.scheduleRef())
                .transactionFee(result.transactionFeeCharged())
                .transferList(result.transferList())
                .tokenTransferLists(result.tokenTransferLists())
                .automaticTokenAssociations(result.automaticTokenAssociations())
                .assessedCustomFees(result.assessedCustomFees())
                .paidStakingRewards(result.paidStakingRewards());
        final var function = context.functionality();
        switch (function) {
            case CONTRACT_CALL, CONTRACT_CREATE, CONTRACT_DELETE, CONTRACT_UPDATE, ETHEREUM_TRANSACTION -> {
                if (function == CONTRACT_CALL) {
                    recordBuilder.contractCallResult(outputValueIfPresent(
                            TransactionOutput::hasContractCall,
                            logAwareResultExtractor(CONTRACT_CALL_EXTRACTOR, logs),
                            outputs));
                } else if (function == CONTRACT_CREATE) {
                    recordBuilder.contractCreateResult(outputValueIfPresent(
                            TransactionOutput::hasContractCreate,
                            logAwareResultExtractor(CONTRACT_CREATE_EXTRACTOR, logs),
                            outputs));
                } else if (function == ETHEREUM_TRANSACTION) {
                    final var ethOutput = outputValueIfPresent(
                            TransactionOutput::hasEthereumCall, TransactionOutput::ethereumCallOrThrow, outputs);
                    if (ethOutput != null) {
                        recordBuilder.ethereumHash(ethOutput.ethereumHash());
                        switch (ethOutput.ethResult().kind()) {
                            case ETHEREUM_CALL_RESULT ->
                                recordBuilder.contractCallResult(
                                        resultWithLogs(ethOutput.ethereumCallResultOrThrow(), logs));
                            case ETHEREUM_CREATE_RESULT ->
                                recordBuilder.contractCreateResult(
                                        resultWithLogs(ethOutput.ethereumCreateResultOrThrow(), logs));
                        }
                    }
                }
            }
            default -> {
                final var synthResult =
                        outputValueIfPresent(TransactionOutput::hasContractCall, CONTRACT_CALL_EXTRACTOR, outputs);
                if (synthResult != null) {
                    recordBuilder.contractCallResult(synthResult);
                }
                switch (function) {
                    case CRYPTO_CREATE, CRYPTO_UPDATE ->
                        recordBuilder.evmAddress(((CryptoOpContext) context).evmAddress());
                    case TOKEN_AIRDROP ->
                        recordBuilder.newPendingAirdrops(((AirdropOpContext) context).pendingAirdropRecords());
                    case UTIL_PRNG -> {
                        final var prngOutput = outputValueIfPresent(
                                TransactionOutput::hasUtilPrng, TransactionOutput::utilPrngOrThrow, outputs);
                        if (prngOutput != null) {
                            switch (prngOutput.entropy().kind()) {
                                case PRNG_BYTES -> recordBuilder.prngBytes(prngOutput.prngBytesOrThrow());
                                case PRNG_NUMBER -> recordBuilder.prngNumber(prngOutput.prngNumberOrThrow());
                            }
                        }
                    }
                }
            }
        }
        return recordBuilder.receipt(translateReceipt(context, result, outputs)).build();
    }

    private Function<TransactionOutput, ContractFunctionResult> logAwareResultExtractor(
            @NonNull final Function<TransactionOutput, ContractFunctionResult> extractor,
            @Nullable final List<EvmTransactionLog> logs) {
        return output -> resultWithLogs(extractor.apply(output), logs);
    }

    private ContractFunctionResult resultWithLogs(
            @NonNull final ContractFunctionResult result, @Nullable final List<EvmTransactionLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return result;
        } else {
            final List<Log> besuLogs = new ArrayList<>(logs.size());
            final List<ContractLoginfo> verboseLogs = new ArrayList<>(logs.size());
            for (final var log : logs) {
                final var paddedTopics =
                        log.topics().stream().map(ConversionUtils::leftPad32).toList();
                final var besuLog = asBesuLog(log, paddedTopics);
                besuLogs.add(besuLog);
                verboseLogs.add(ContractLoginfo.newBuilder()
                        .contractID(log.contractIdOrThrow())
                        .topic(paddedTopics)
                        .bloom(bloomFor(besuLog))
                        .data(log.data())
                        .build());
            }
            return result.copyBuilder()
                    .bloom(bloomForAll(besuLogs))
                    .logInfo(verboseLogs)
                    .build();
        }
    }

    private static <T> @Nullable T outputValueIfPresent(
            @NonNull final Predicate<TransactionOutput> filter,
            @NonNull final Function<TransactionOutput, T> extractor,
            @NonNull final TransactionOutput... outputs) {
        for (final var output : outputs) {
            if (filter.test(output)) {
                return extractor.apply(output);
            }
        }
        return null;
    }
}
