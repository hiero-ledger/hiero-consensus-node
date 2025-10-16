// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_CONTRACT_STORAGE_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.errorMessageFor;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.accessTrackerFor;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.hasActionSidecarsEnabled;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asHederaLogs;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.bloomForAll;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjLogsFrom;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.txStorageUsageFrom;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.trace.EvmTransactionLog;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.contract.InternalCallContext;
import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActionType;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.service.contract.impl.exec.ActionSidecarContentTracer;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.RootProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.TxStorageUsage;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.List;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.log.Log;

public record HederaEvmTransactionResult(
        long gasUsed,
        long gasPrice,
        @NonNull AccountID senderId,
        @Nullable ContractID recipientId,
        @Nullable ContractID recipientEvmAddress,
        @NonNull Bytes output,
        @Nullable ExceptionalHaltReason haltReason,
        @Nullable Bytes revertReason,
        @NonNull @Deprecated List<Log> logs,
        @Nullable List<EvmTransactionLog> evmLogs,
        @Nullable ResponseCodeEnum finalStatus,
        @Nullable List<ContractAction> actions,
        @Nullable Long signerNonce,
        @Nullable TxStorageUsage txStorageUsage) {
    public HederaEvmTransactionResult {
        requireNonNull(senderId);
        requireNonNull(output);
        requireNonNull(logs);
    }

    private static final Bytes MAX_STORAGE_EXCEEDED_REASON = Bytes.wrap(MAX_CONTRACT_STORAGE_EXCEEDED.name());
    private static final Bytes MAX_TOTAL_STORAGE_EXCEEDED_REASON =
            Bytes.wrap(MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED.name());
    private static final Bytes INSUFFICIENT_GAS_REASON = Bytes.wrap(INSUFFICIENT_GAS.name());
    private static final Bytes INVALID_CONTRACT_REASON = Bytes.wrap(INVALID_CONTRACT_ID.name());
    private static final Bytes MAX_CHILD_RECORDS_EXCEEDED_REASON = Bytes.wrap(MAX_CHILD_RECORDS_EXCEEDED.name());
    private static final Bytes INSUFFICIENT_TX_FEE_REASON = Bytes.wrap(INSUFFICIENT_TX_FEE.name());
    private static final Bytes INSUFFICIENT_PAYER_BALANCE_REASON = Bytes.wrap(INSUFFICIENT_PAYER_BALANCE.name());
    private static final Bytes CONTRACT_EXECUTION_EXCEPTION_REASON = Bytes.wrap(CONTRACT_EXECUTION_EXCEPTION.name());

    /**
     * Converts this result to a {@link ContractFunctionResult} for a transaction based on the given
     * {@link RootProxyWorldUpdater} and maybe {@link EthTxData}.
     *
     * @param ethTxData the Ethereum transaction data if relevant
     * @param updater the world updater
     * @param callData the call data if relevant
     * @return the result
     */
    public ContractFunctionResult asProtoResultOf(
            @Nullable final EthTxData ethTxData,
            @NonNull final RootProxyWorldUpdater updater,
            @Nullable final Bytes callData) {
        if (haltReason != null) {
            return withMaybeEthFields(asUncommittedFailureResult(errorMessageFor(haltReason)), ethTxData, callData);
        } else if (revertReason != null) {
            // This curious presentation of the revert reason is needed for backward compatibility
            return withMaybeEthFields(
                    asUncommittedFailureResult(errorMessageForRevert(revertReason)), ethTxData, callData);
        } else {
            return withMaybeEthFields(asSuccessResultForCommitted(updater), ethTxData, callData);
        }
    }

    /**
     * Converts this result to a {@link ContractFunctionResult} for a transaction based on the given
     * {@link RootProxyWorldUpdater} and maybe {@link EthTxData}.
     *
     * @param ethTxData the Ethereum transaction data if relevant
     * @param callData the call data if relevant
     * @return the result
     */
    public EvmTransactionResult asEvmTxResultOf(@Nullable final EthTxData ethTxData, @Nullable final Bytes callData) {
        if (haltReason != null) {
            return txWithMaybeEthFields(
                    asUncommittedFailureResultBuilder(errorMessageFor(haltReason)), ethTxData, callData);
        } else if (revertReason != null) {
            // This curious presentation of the revert reason is needed for backward compatibility
            return txWithMaybeEthFields(
                    asUncommittedFailureResultBuilder(errorMessageForRevert(revertReason)), ethTxData, callData);
        } else {
            return txWithMaybeEthFields(asSuccessResultForCommittedBuilder(), ethTxData, callData);
        }
    }

    /**
     * Converts this result to a {@link ContractFunctionResult} for a query response.
     * @return the result
     */
    public ContractFunctionResult asQueryResult(@NonNull final ProxyWorldUpdater updater) {
        if (haltReason != null) {
            return asUncommittedFailureResult(errorMessageFor(haltReason)).build();
        } else if (revertReason != null) {
            return asUncommittedFailureResult(errorMessageForRevert(revertReason))
                    .build();
        } else {
            return asSuccessResultForQuery(updater);
        }
    }

    /**
     * Converts this result to a {@link ContractFunctionResult} for a query response.
     * @return the result
     */
    public EvmTransactionResult asEvmQueryResult() {
        if (haltReason != null) {
            return asUncommittedFailureResultBuilder(errorMessageFor(haltReason))
                    .build();
        } else if (revertReason != null) {
            return asUncommittedFailureResultBuilder(errorMessageForRevert(revertReason))
                    .build();
        } else {
            return txAsSuccessResultForQuery();
        }
    }

    /**
     * Returns the final status of this transaction result.
     *
     * @return the status
     */
    public ResponseCodeEnum finalStatus() {
        if (finalStatus != null) {
            return finalStatus;
        } else if (haltReason != null) {
            return CustomExceptionalHaltReason.statusFor(haltReason);
        } else if (revertReason != null) {
            if (revertReason.equals(MAX_STORAGE_EXCEEDED_REASON)) {
                return MAX_CONTRACT_STORAGE_EXCEEDED;
            } else if (revertReason.equals(MAX_TOTAL_STORAGE_EXCEEDED_REASON)) {
                return MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED;
            } else if (revertReason.equals(INSUFFICIENT_GAS_REASON)) {
                return INSUFFICIENT_GAS;
            } else if (revertReason.equals(INVALID_CONTRACT_REASON)) {
                return INVALID_CONTRACT_ID;
            } else if (revertReason.equals(MAX_CHILD_RECORDS_EXCEEDED_REASON)) {
                return MAX_CHILD_RECORDS_EXCEEDED;
            } else if (revertReason.equals(INSUFFICIENT_TX_FEE_REASON)) {
                return INSUFFICIENT_TX_FEE;
            } else if (revertReason.equals(INSUFFICIENT_PAYER_BALANCE_REASON)) {
                return INSUFFICIENT_PAYER_BALANCE;
            } else if (revertReason.equals(CONTRACT_EXECUTION_EXCEPTION_REASON)) {
                return CONTRACT_EXECUTION_EXCEPTION;
            } else {
                return CONTRACT_REVERT_EXECUTED;
            }
        } else {
            return SUCCESS;
        }
    }

    /**
     * Create a result for a transaction that succeeded.
     *
     * @param gasUsed the gas used by the transaction
     * @param senderId the Hedera id of the sender
     * @param recipientId the Hedera numbered id of the receiving or created contract
     * @param recipientEvmAddress the Hedera aliased id of the receiving or created contract
     * @param frame the root frame for the transaction
     * @param tracer the Hedera-specific tracer for the EVM transaction's actions
     * @param entityIdFactory the Hedera entity id factory
     * @return the result
     */
    public static HederaEvmTransactionResult successFrom(
            final long gasUsed,
            @NonNull final AccountID senderId,
            @NonNull final ContractID recipientId,
            @NonNull final ContractID recipientEvmAddress,
            @NonNull final MessageFrame frame,
            @NonNull final ActionSidecarContentTracer tracer,
            @NonNull final EntityIdFactory entityIdFactory) {
        requireNonNull(senderId);
        requireNonNull(recipientId);
        requireNonNull(recipientEvmAddress);
        requireNonNull(frame);
        requireNonNull(tracer);
        requireNonNull(entityIdFactory);
        final var besuLogs = frame.getLogs();
        final var evmLogs = besuLogs.isEmpty() ? null : asHederaLogs(besuLogs, entityIdFactory);
        return new HederaEvmTransactionResult(
                gasUsed,
                frame.getGasPrice().toLong(),
                senderId,
                recipientId,
                recipientEvmAddress,
                tuweniToPbjBytes(frame.getOutputData()),
                null,
                null,
                besuLogs,
                evmLogs,
                null,
                maybeActionsFrom(frame, tracer),
                null,
                null);
    }

    /**
     * Create a result for a transaction that failed.
     *
     * @param gasUsed           the gas used by the transaction
     * @param senderId          the Hedera id of the transaction sender
     * @param frame             the initial frame of the transaction
     * @param recipientId       if known, the Hedera id of the receiving contract
     * @param tracer            the Hedera-specific tracer for the EVM transaction's actions
     * @return the result
     */
    public static HederaEvmTransactionResult failureFrom(
            final long gasUsed,
            @NonNull final AccountID senderId,
            @NonNull final MessageFrame frame,
            @Nullable final ContractID recipientId,
            @NonNull final ActionSidecarContentTracer tracer) {
        requireNonNull(frame);
        requireNonNull(tracer);
        final var txStorageUsage = txStorageUsageFrom(null, accessTrackerFor(frame), false);
        return new HederaEvmTransactionResult(
                gasUsed,
                frame.getGasPrice().toLong(),
                requireNonNull(senderId),
                recipientId,
                null,
                Bytes.EMPTY,
                frame.getExceptionalHaltReason().orElse(null),
                frame.getRevertReason().map(ConversionUtils::tuweniToPbjBytes).orElse(null),
                Collections.emptyList(),
                null,
                null,
                maybeActionsFrom(frame, tracer),
                null,
                txStorageUsage);
    }

    /**
     * Create a result for a transaction that failed due to resource exhaustion.
     *
     * @param gasUsed  the gas used by the transaction
     * @param gasPrice the gas price of the transaction
     * @param reason   the reason for the failure
     * @return the result
     */
    public static HederaEvmTransactionResult resourceExhaustionFrom(
            @NonNull final AccountID senderId,
            final long gasUsed,
            final long gasPrice,
            @NonNull final ResponseCodeEnum reason) {
        requireNonNull(reason);
        return new HederaEvmTransactionResult(
                gasUsed,
                gasPrice,
                requireNonNull(senderId),
                null,
                null,
                Bytes.EMPTY,
                null,
                Bytes.wrap(reason.name()),
                Collections.emptyList(),
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * Create a result for a transaction that failed due to validation exceptions.
     *
     * @param senderId    the sender of the EVM transaction
     * @param recipientId the recipient of the EVM transaction
     * @param reason      the reason for the failure
     * @return the result
     */
    public static HederaEvmTransactionResult fromAborted(
            @NonNull final AccountID senderId,
            @Nullable ContractID recipientId,
            @NonNull final ResponseCodeEnum reason) {
        requireNonNull(senderId);
        requireNonNull(reason);
        return new HederaEvmTransactionResult(
                0,
                0,
                senderId,
                recipientId,
                null,
                Bytes.EMPTY,
                null,
                Bytes.wrap(reason.name().getBytes()),
                List.of(),
                null,
                reason,
                null,
                null,
                null);
    }

    /**
     * Returns the EVM address of the recipient if it was created in the given updater.
     * @param updater the updater to check for created contracts
     * @return the EVM address of the recipient if it was created in the updater, or null if not
     */
    public @Nullable Bytes evmAddressIfCreatedIn(@NonNull final RootProxyWorldUpdater updater) {
        return recipientEvmAddressIfCreatedIn(updater.getCreatedContractIds());
    }

    private ContractFunctionResult withMaybeEthFields(
            @NonNull final ContractFunctionResult.Builder builder,
            @Nullable final EthTxData ethTxData,
            @Nullable final Bytes callData) {
        if (ethTxData != null) {
            builder.gas(ethTxData.gasLimit())
                    .amount(ethTxData.getAmount())
                    .senderId(senderId)
                    .functionParameters(requireNonNull(callData));
        }
        return builder.build();
    }

    private EvmTransactionResult txWithMaybeEthFields(
            @NonNull final EvmTransactionResult.Builder builder,
            @Nullable final EthTxData ethTxData,
            @Nullable final Bytes callData) {
        if (ethTxData != null) {
            builder.senderId(senderId)
                    .internalCallContext(new InternalCallContext(
                            ethTxData.gasLimit(), ethTxData.getAmount(), requireNonNull(callData)));
        }
        return builder.build();
    }

    private ContractFunctionResult.Builder asUncommittedFailureResult(@NonNull final String errorMessage) {
        requireNonNull(errorMessage);
        final var builder = ContractFunctionResult.newBuilder()
                .gasUsed(gasUsed)
                .errorMessage(errorMessage)
                .signerNonce(signerNonce);
        // checking first action.callType is CREATE to indicate 'create contract' call
        // we are not setting recipientId as contractID for create contract call  because failed block/receipt should
        // not contain contractID
        if (actions == null
                || actions.isEmpty()
                || !ContractActionType.CREATE.equals(actions.getFirst().callType())) {
            builder.contractID(recipientId);
        }
        return builder;
    }

    private EvmTransactionResult.Builder asUncommittedFailureResultBuilder(@NonNull final String errorMessage) {
        requireNonNull(errorMessage);
        final var builder = EvmTransactionResult.newBuilder().gasUsed(gasUsed).errorMessage(errorMessage);
        // checking first action.callType is CREATE to indicate 'create contract' call
        // we are not setting recipientId as contractID for create contract call  because failed block/receipt should
        // not contain contractID
        if (actions == null
                || actions.isEmpty()
                || !ContractActionType.CREATE.equals(actions.getFirst().callType())) {
            builder.contractId(recipientId);
        }
        return builder;
    }

    private ContractFunctionResult.Builder asSuccessResultForCommitted(@NonNull final RootProxyWorldUpdater updater) {
        final var createdIds = updater.getCreatedContractIds();
        return ContractFunctionResult.newBuilder()
                .gasUsed(gasUsed)
                .bloom(bloomForAll(logs))
                .contractCallResult(output)
                .contractID(recipientId)
                .createdContractIDs(createdIds)
                .logInfo(pbjLogsFrom(updater.entityIdFactory(), logs))
                .evmAddress(recipientEvmAddressIfCreatedIn(createdIds))
                .contractNonces(updater.getUpdatedContractNonces())
                .errorMessage("")
                .signerNonce(signerNonce);
    }

    private EvmTransactionResult.Builder asSuccessResultForCommittedBuilder() {
        return EvmTransactionResult.newBuilder()
                .gasUsed(gasUsed)
                .resultData(output)
                .contractId(recipientId)
                .errorMessage("");
    }

    private ContractFunctionResult asSuccessResultForQuery(@NonNull final ProxyWorldUpdater updater) {
        return ContractFunctionResult.newBuilder()
                .gasUsed(gasUsed)
                .bloom(bloomForAll(logs))
                .contractCallResult(output)
                .contractID(recipientId)
                .logInfo(pbjLogsFrom(updater.entityIdFactory(), logs))
                .errorMessage("")
                .signerNonce(signerNonce)
                .build();
    }

    private EvmTransactionResult txAsSuccessResultForQuery() {
        return EvmTransactionResult.newBuilder()
                .gasUsed(gasUsed)
                .resultData(output)
                .contractId(recipientId)
                .errorMessage("")
                .build();
    }

    private @Nullable Bytes recipientEvmAddressIfCreatedIn(@NonNull final List<ContractID> contractIds) {
        return contractIds.contains(recipientId)
                ? requireNonNull(recipientEvmAddress).evmAddressOrThrow()
                : null;
    }

    public boolean isSuccess() {
        return revertReason == null && haltReason == null;
    }

    private static @Nullable List<ContractAction> maybeActionsFrom(
            @NonNull final MessageFrame frame, @NonNull final ActionSidecarContentTracer tracer) {
        return hasActionSidecarsEnabled(frame) ? tracer.contractActions() : null;
    }

    private static String errorMessageForRevert(@NonNull final Bytes reason) {
        requireNonNull(reason);
        return "0x" + reason.toHex();
    }

    public HederaEvmTransactionResult withSignerNonce(@Nullable final Long signerNonce) {
        return new HederaEvmTransactionResult(
                gasUsed,
                gasPrice,
                senderId,
                recipientId,
                recipientEvmAddress,
                output,
                haltReason,
                revertReason,
                logs,
                evmLogs,
                finalStatus,
                actions,
                signerNonce,
                txStorageUsage);
    }

    public HederaEvmTransactionResult withTxStorageUsage(@Nullable final TxStorageUsage txStorageUsage) {
        return new HederaEvmTransactionResult(
                gasUsed,
                gasPrice,
                senderId,
                recipientId,
                recipientEvmAddress,
                output,
                haltReason,
                revertReason,
                logs,
                evmLogs,
                finalStatus,
                actions,
                signerNonce,
                txStorageUsage);
    }
}
