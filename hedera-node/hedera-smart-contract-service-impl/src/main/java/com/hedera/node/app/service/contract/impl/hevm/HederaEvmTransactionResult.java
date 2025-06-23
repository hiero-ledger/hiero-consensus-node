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
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.proxyUpdaterFor;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asHederaLogs;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asPbjSlotUsages;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asPbjStateChanges;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.bloomForAll;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjLogsFrom;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.trace.ContractSlotUsage;
import com.hedera.hapi.block.stream.trace.EvmTransactionLog;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActionType;
import com.hedera.hapi.streams.ContractStateChanges;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.service.contract.impl.exec.ActionSidecarContentTracer;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.RootProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.StorageAccesses;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.EntityIdFactory;
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
        @Nullable @Deprecated ContractStateChanges stateChanges,
        @Nullable List<ContractSlotUsage> slotUsages,
        @Nullable ResponseCodeEnum finalStatus,
        @Nullable List<ContractAction> actions,
        @Nullable Long signerNonce) {
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
     * {@link RootProxyWorldUpdater}.
     *
     * @param updater the world updater
     * @return the result
     */
    public ContractFunctionResult asProtoResultOf(@NonNull final RootProxyWorldUpdater updater) {
        return asProtoResultOf(null, updater);
    }

    /**
     * Converts this result to a {@link ContractFunctionResult} for a transaction based on the given
     * {@link RootProxyWorldUpdater} and maybe {@link EthTxData}.
     *
     * @param ethTxData the Ethereum transaction data if relevant
     * @param updater   the world updater
     * @return the result
     */
    public ContractFunctionResult asProtoResultOf(
            @Nullable final EthTxData ethTxData, @NonNull final RootProxyWorldUpdater updater) {
        if (haltReason != null) {
            return withMaybeEthFields(asUncommittedFailureResult(errorMessageFor(haltReason)), ethTxData);
        } else if (revertReason != null) {
            // This curious presentation of the revert reason is needed for backward compatibility
            return withMaybeEthFields(asUncommittedFailureResult(errorMessageForRevert(revertReason)), ethTxData);
        } else {
            return withMaybeEthFields(asSuccessResultForCommitted(updater), ethTxData);
        }
    }

    /**
     * Converts this result to a {@link ContractFunctionResult} for a query response.
     *
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
        final var storageAccesses = maybeAllStateChangesFrom(frame);
        final var streamMode = FrameUtils.configOf(frame)
                .getConfigData(BlockStreamConfig.class)
                .streamMode();
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
                streamMode != BLOCKS ? asPbjStateChanges(storageAccesses) : null,
                streamMode != RECORDS ? asPbjSlotUsages(storageAccesses) : null,
                null,
                maybeActionsFrom(frame, tracer),
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
        final var storageAccesses = maybeReadOnlyStateChangesFrom(frame);
        final var streamMode = FrameUtils.configOf(frame)
                .getConfigData(BlockStreamConfig.class)
                .streamMode();
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
                streamMode != BLOCKS ? asPbjStateChanges(storageAccesses) : null,
                streamMode != RECORDS ? asPbjSlotUsages(storageAccesses) : null,
                null,
                maybeActionsFrom(frame, tracer),
                null);
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
                null,
                null,
                reason,
                null,
                null);
    }

    private ContractFunctionResult withMaybeEthFields(
            @NonNull final ContractFunctionResult.Builder builder, @Nullable final EthTxData ethTxData) {
        if (ethTxData != null) {
            builder.gas(ethTxData.gasLimit())
                    .amount(ethTxData.getAmount())
                    .senderId(senderId)
                    .functionParameters(Bytes.wrap(ethTxData.callData()));
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

    private @Nullable Bytes recipientEvmAddressIfCreatedIn(@NonNull final List<ContractID> contractIds) {
        return contractIds.contains(recipientId)
                ? requireNonNull(recipientEvmAddress).evmAddressOrThrow()
                : null;
    }

    public boolean isSuccess() {
        return revertReason == null && haltReason == null;
    }

    private static @Nullable List<StorageAccesses> maybeAllStateChangesFrom(@NonNull final MessageFrame frame) {
        return storageAccessesFrom(frame, true);
    }

    private static @Nullable List<ContractAction> maybeActionsFrom(
            @NonNull final MessageFrame frame, @NonNull final ActionSidecarContentTracer tracer) {
        return hasActionSidecarsEnabled(frame) ? tracer.contractActions() : null;
    }

    private static @Nullable List<StorageAccesses> maybeReadOnlyStateChangesFrom(@NonNull final MessageFrame frame) {
        return storageAccessesFrom(frame, false);
    }

    private static String errorMessageForRevert(@NonNull final Bytes reason) {
        requireNonNull(reason);
        return "0x" + reason.toHex();
    }

    private static @Nullable List<StorageAccesses> storageAccessesFrom(
            @NonNull final MessageFrame frame, final boolean includeWrites) {
        requireNonNull(frame);
        final var accessTracker = accessTrackerFor(frame);
        if (accessTracker == null) {
            return null;
        } else {
            final List<StorageAccesses> accesses;
            if (includeWrites) {
                final var worldUpdater = proxyUpdaterFor(frame);
                accesses = accessTracker.getReadsMergedWith(worldUpdater.pendingStorageUpdates());
            } else {
                accesses = accessTracker.getJustReads();
            }
            return accesses;
        }
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
                stateChanges,
                slotUsages,
                finalStatus,
                actions,
                signerNonce);
    }
}
