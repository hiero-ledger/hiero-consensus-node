// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.throttle;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.populateEthTxData;
import static com.hedera.node.app.spi.workflows.HandleContext.ConsensusThrottling.ON;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.NODE;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.throttle.ThrottleAccumulator.canAutoAssociate;
import static com.hedera.node.app.throttle.ThrottleAccumulator.canAutoCreate;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.throttle.CongestionThrottleService;
import com.hedera.node.app.throttle.NetworkUtilizationManager;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.workflows.OpWorkflowMetrics;
import com.hedera.node.app.workflows.handle.Dispatch;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class DispatchUsageManager {
    public static final Set<HederaFunctionality> CONTRACT_OPERATIONS =
            EnumSet.of(CONTRACT_CREATE, CONTRACT_CALL, ETHEREUM_TRANSACTION);

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
            if (canAutoCreate(function)) {
                reclaimFailedCryptoCreateCapacity(dispatch);
            }
            if (canAutoAssociate(function)) {
                reclaimFailedTokenAssociate(dispatch);
            }
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
     * Reclaims the throttle capacity for a failed dispatch that tried to implicitly
     * perform {@link HederaFunctionality#CRYPTO_CREATE} operations.
     *
     * @param dispatch the dispatch
     */
    private void reclaimFailedCryptoCreateCapacity(@NonNull final Dispatch dispatch) {
        final var readableAccountStore = dispatch.readableStoreFactory().getStore(ReadableAccountStore.class);
        final var numImplicitCreations =
                throttleServiceManager.numImplicitCreations(dispatch.txnInfo().txBody(), readableAccountStore);
        if (usedSelfFrontendThrottleCapacity(
                numImplicitCreations, dispatch.txnInfo().txBody())) {
            throttleServiceManager.reclaimFrontendThrottleCapacity(numImplicitCreations, CRYPTO_CREATE);
        }
    }

    /**
     * Reclaims the throttle capacity for a failed dispatch that tried to
     * perform {@link HederaFunctionality#TOKEN_ASSOCIATE_TO_ACCOUNT} operations for Auto Association.
     *
     * @param dispatch the dispatch
     */
    private void reclaimFailedTokenAssociate(@NonNull final Dispatch dispatch) {
        final var readableTokenRelStore = dispatch.readableStoreFactory().getStore(ReadableTokenRelationStore.class);
        final var numAutoAssociations =
                throttleServiceManager.numAutoAssociations(dispatch.txnInfo().txBody(), readableTokenRelStore);
        if (usedSelfFrontendThrottleCapacity(
                numAutoAssociations, dispatch.txnInfo().txBody())) {
            throttleServiceManager.reclaimFrontendThrottleCapacity(numAutoAssociations, TOKEN_ASSOCIATE_TO_ACCOUNT);
        }
    }

    /**
     * Returns true if the transaction used frontend throttle capacity on this node.
     *
     * @param numUsedCapacity the number of used capacity for either create ot auto associate operations
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
        } else {
            return txnBody.contractCallOrElse(ContractCallTransactionBody.DEFAULT)
                    .gas();
        }
    }
}
