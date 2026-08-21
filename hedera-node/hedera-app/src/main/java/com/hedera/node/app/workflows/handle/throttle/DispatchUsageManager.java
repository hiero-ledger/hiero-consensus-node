// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.throttle;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.HederaFunctionality.HOOK_DISPATCH;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.populateEthTxData;
import static com.hedera.node.app.spi.workflows.HandleContext.ConsensusThrottling.ON;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.NODE;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.throttle.ThrottleAccumulator.canAutoAssociate;
import static com.hedera.node.app.throttle.ThrottleAccumulator.canAutoCreate;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.EvmHookCall;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.HookCall;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.hooks.HookExecution;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.throttle.CongestionThrottleService;
import com.hedera.node.app.throttle.NetworkUtilizationManager;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.workflows.OpWorkflowMetrics;
import com.hedera.node.app.workflows.handle.Dispatch;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.EntitiesConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class DispatchUsageManager {
    public static final Set<HederaFunctionality> CONTRACT_OPERATIONS =
            EnumSet.of(CONTRACT_CREATE, CONTRACT_CALL, ETHEREUM_TRANSACTION, HOOK_DISPATCH);

    private final NetworkInfo networkInfo;
    private final OpWorkflowMetrics opWorkflowMetrics;
    private final ThrottleServiceManager throttleServiceManager;
    private final NetworkUtilizationManager networkUtilizationManager;

    @Inject
    public DispatchUsageManager(
            @NonNull final NetworkInfo networkInfo,
            @NonNull final OpWorkflowMetrics opWorkflowMetrics,
            @NonNull final ThrottleServiceManager throttleServiceManager,
            @NonNull final NetworkUtilizationManager networkUtilizationManager) {
        this.networkInfo = requireNonNull(networkInfo);
        this.opWorkflowMetrics = requireNonNull(opWorkflowMetrics);
        this.throttleServiceManager = requireNonNull(throttleServiceManager);
        this.networkUtilizationManager = requireNonNull(networkUtilizationManager);
    }

    /**
     * Tracks usage of the given dispatch before it is sent to a handler. This is only checked for contract
     * operations now. This code will be moved into the contract-service module in the future.
     *
     * @param dispatch the dispatch
     * @throws ThrottleException if the dispatch should be throttled
     */
    public void screenForCapacity(@NonNull final Dispatch dispatch) throws ThrottleException {
        if (dispatch.throttleStrategy() == ON) {
            final var readableStates = dispatch.stack().getReadableStates(CongestionThrottleService.NAME);
            // reset throttles for every dispatch before we track the usage. This is to ensure that
            // when the user transaction fails, we release the capacity taken at consensus by child transactions.
            throttleServiceManager.resetThrottlesUnconditionally(readableStates);
            final var isThrottled =
                    networkUtilizationManager.trackTxn(dispatch.txnInfo(), dispatch.consensusNow(), dispatch.stack());
            if (networkUtilizationManager.wasLastTxnGasThrottled()) {
                throw ThrottleException.newGasThrottleException();
            } else if (isThrottled) {
                throw ThrottleException.newNativeThrottleException();
            }
        }
    }

    /**
     * Tracks the final work done by handling this user transaction.
     * @param dispatch the dispatch
     */
    public void finalizeAndSaveUsage(@NonNull final Dispatch dispatch) {
        final var function = dispatch.txnInfo().functionality();
        if (CONTRACT_OPERATIONS.contains(function)) {
            leakUnusedGas(dispatch);
        }
        if ((dispatch.txnCategory() == USER || dispatch.txnCategory() == NODE)
                && dispatch.streamBuilder().status() != SUCCESS) {
            reclaimFailedFrontendCapacity(dispatch, function);
        }
        throttleServiceManager.saveThrottleSnapshotsAndCongestionLevelStartsTo(dispatch.stack());
    }

    /**
     * Tracks the work done for a dispatch that stopped after charging fees.
     * @param dispatch the dispatch
     */
    public void trackFeePayments(@NonNull final Dispatch dispatch) {
        networkUtilizationManager.trackFeePayments(dispatch.consensusNow(), dispatch.stack());
    }

    /**
     * Leaks the unused gas for a contract dispatch.
     *
     * @param dispatch the dispatch
     */
    private void leakUnusedGas(@NonNull final Dispatch dispatch) {
        final var builder = dispatch.streamBuilder();
        // (FUTURE) There can be cases where the EVM halts and consumes all gas even though not
        // much actual work was done; in such cases, the gas used is still reported to be at
        // least 80% of the gas limit. If we want to be more precise, we can probably use the
        // EVM action tracer to get a better estimate of the actual gas used and the gas limit.
        if (builder.hasContractResult()) {
            final var gasUsed = builder.getGasUsedForContractTxn();
            opWorkflowMetrics.addGasUsed(gasUsed);
            final var contractsConfig = dispatch.config().getConfigData(ContractsConfig.class);
            if (contractsConfig.throttleThrottleByGas()) {
                final var txnInfo = dispatch.txnInfo();
                final var gasLimitForContractTx = getGasLimitForContractTx(txnInfo.txBody(), txnInfo.functionality());
                final var excessAmount = gasLimitForContractTx - gasUsed;
                networkUtilizationManager.leakUnusedGasPreviouslyReserved(txnInfo, excessAmount);
            }
        }
    }

    /**
     * Reclaims the frontend throttle capacity for a failed dispatch that implicitly performed
     * {@link HederaFunctionality#CRYPTO_CREATE} or {@link HederaFunctionality#TOKEN_ASSOCIATE_TO_ACCOUNT}
     * operations.
     *
     * <p>This mirrors the claim made at ingest by {@code ThrottleAccumulator.shouldThrottleCryptoTransfer}: a
     * transfer charges capacity for exactly one of {implicit creations, auto associations} - implicit creations
     * take precedence, and auto associations are only charged when
     * {@link EntitiesConfig#unlimitedAutoAssociationsEnabled()} is set. The reclaim therefore undoes exactly that
     * one leg, and leaks implicit-creation capacity back into the same (normal or high-volume) bucket it was
     * charged to.
     *
     * @param dispatch the failed dispatch
     * @param function the functionality of the dispatch
     */
    private void reclaimFailedFrontendCapacity(
            @NonNull final Dispatch dispatch, @NonNull final HederaFunctionality function) {
        final var txnBody = dispatch.txnInfo().txBody();
        if (canAutoCreate(function)) {
            final var readableAccountStore = dispatch.readableStoreFactory().readableStore(ReadableAccountStore.class);
            final var numImplicitCreations = throttleServiceManager.numImplicitCreations(txnBody, readableAccountStore);
            if (numImplicitCreations > 0) {
                if (usedSelfFrontendThrottleCapacity(numImplicitCreations, txnBody)) {
                    final var useHighVolumeBucket = throttleServiceManager.usesHighVolumeBucketForImplicitCreations(
                            txnBody, function, numImplicitCreations);
                    throttleServiceManager.reclaimFrontendThrottleCapacity(
                            numImplicitCreations, CRYPTO_CREATE, useHighVolumeBucket);
                }
                // Implicit creations took precedence at claim time, so the auto-association leg was never
                // charged and must not be reclaimed.
                return;
            }
        }
        if (canAutoAssociate(function)) {
            final var readableTokenRelStore =
                    dispatch.readableStoreFactory().readableStore(ReadableTokenRelationStore.class);
            final var numAutoAssociations = throttleServiceManager.numAutoAssociations(txnBody, readableTokenRelStore);
            if (numAutoAssociations > 0
                    && dispatch.config().getConfigData(EntitiesConfig.class).unlimitedAutoAssociationsEnabled()
                    && usedSelfFrontendThrottleCapacity(numAutoAssociations, txnBody)) {
                // Auto associations for a CRYPTO_TRANSFER are always claimed against the normal bucket
                throttleServiceManager.reclaimFrontendThrottleCapacity(
                        numAutoAssociations, TOKEN_ASSOCIATE_TO_ACCOUNT, false);
            }
        }
    }

    /**
     * Returns true if the transaction used frontend throttle capacity on this node.
     *
     * @param numUsedCapacity the number of used capacity for either create or auto associate operations
     * @param txnBody         the transaction body
     * @return true if the transaction used frontend throttle capacity on this node
     */
    private boolean usedSelfFrontendThrottleCapacity(
            final int numUsedCapacity, @NonNull final TransactionBody txnBody) {
        return numUsedCapacity > 0
                && txnBody.nodeAccountIDOrThrow()
                        .equals(networkInfo.selfNodeInfo().accountId());
    }

    /**
     * Returns the gas limit for a contract transaction.
     *
     * @param txnBody  the transaction body
     * @param function the functionality
     * @return the gas limit for a contract transaction
     */
    private static long getGasLimitForContractTx(
            @NonNull final TransactionBody txnBody, @NonNull final HederaFunctionality function) {
        if (function == CONTRACT_CREATE) {
            return txnBody.contractCreateInstanceOrElse(ContractCreateTransactionBody.DEFAULT)
                    .gas();
        } else if (function == ETHEREUM_TRANSACTION) {
            final var rawEthTxn = txnBody.ethereumTransactionOrElse(EthereumTransactionBody.DEFAULT);
            final var ethTxData = populateEthTxData(rawEthTxn.ethereumData().toByteArray());
            return ethTxData != null ? ethTxData.gasLimit() : 0L;
        } else if (function == HOOK_DISPATCH) {
            // For hook dispatch, we consider the gas limit of the first contract call in the hook dispatch
            // transaction body. This is a simplification and may need to be revisited if we want to be more
            // precise.
            return txnBody.hookDispatchOrThrow()
                    .executionOrElse(HookExecution.DEFAULT)
                    .callOrElse(HookCall.DEFAULT)
                    .evmHookCallOrElse(EvmHookCall.DEFAULT)
                    .gasLimit();
        } else {
            return txnBody.contractCallOrElse(ContractCallTransactionBody.DEFAULT)
                    .gas();
        }
    }
}
