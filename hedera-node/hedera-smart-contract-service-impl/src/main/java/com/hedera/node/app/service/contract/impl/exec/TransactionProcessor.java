// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.WRONG_NONCE;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.accessTrackerFor;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionResult.resourceExhaustionFrom;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.contractIDToBesuAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isEvmAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToBesuAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.sponsorCustomizedCreation;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.txStorageUsageFrom;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCharging;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomMessageCallProcessor;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameBuilder;
import com.hedera.node.app.service.contract.impl.exec.utils.OpsDurationCounter;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionResult;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.infra.StorageAccessTracker;
import com.hedera.node.app.service.contract.impl.state.HederaEvmAccount;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.ResourceExhaustedException;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;

/**
 * Modeled after the Besu {@code MainnetTransactionProcessor}, so that all four HAPI
 * contract operations ({@code ContractCall}, {@code ContractCreate}, {@code EthereumTransaction},
 * {@code ContractCallLocal}) can reduce to a single code path.
 */
public class TransactionProcessor {
    private final FrameBuilder frameBuilder;
    private final FrameRunner frameRunner;
    private final CustomGasCharging gasCharging;
    private final CustomMessageCallProcessor messageCall;
    private final ContractCreationProcessor contractCreation;
    private final FeatureFlags featureFlags;

    public TransactionProcessor(
            @NonNull final FrameBuilder frameBuilder,
            @NonNull final FrameRunner frameRunner,
            @NonNull final CustomGasCharging gasCharging,
            @NonNull final CustomMessageCallProcessor messageCall,
            @NonNull final ContractCreationProcessor contractCreation,
            @NonNull final FeatureFlags featureFlags) {
        this.frameBuilder = requireNonNull(frameBuilder);
        this.frameRunner = requireNonNull(frameRunner);
        this.gasCharging = requireNonNull(gasCharging);
        this.messageCall = requireNonNull(messageCall);
        this.contractCreation = requireNonNull(contractCreation);
        this.featureFlags = requireNonNull(featureFlags);
    }

    /**
     * Records the two or three parties involved in a transaction.
     *
     * @param sender the externally-operated account that signed the transaction (AKA the "origin")
     * @param relayer if non-null, the account relayed an Ethereum transaction on behalf of the sender
     * @param receiverAddress the address of the account receiving the top-level call
     */
    private record InvolvedParties(
            @NonNull HederaEvmAccount sender, @Nullable HederaEvmAccount relayer, @NonNull Address receiverAddress) {
        @NonNull
        AccountID senderId() {
            return sender.hederaId();
        }
    }

    /**
     * Returns the feature flags used by this processor.
     *
     * @return the feature flags
     */
    public FeatureFlags featureFlags() {
        return featureFlags;
    }

    /**
     * Process the given transaction, returning the result of running it to completion
     * and committing to the given updater.
     *
     * @param transaction the transaction to process
     * @param updater the world updater to commit to
     * @param context the context to use
     * @param tracer the tracer to use
     * @param config the node configuration
     * @return the result of running the transaction to completion
     */
    public HederaEvmTransactionResult processTransaction(
            @NonNull final HederaEvmTransaction transaction,
            @NonNull final HederaWorldUpdater updater,
            @NonNull final HederaEvmContext context,
            @NonNull final ActionSidecarContentTracer tracer,
            @NonNull final Configuration config,
            @NonNull final OpsDurationCounter opsDurationCounter) {
        final var parties = computeInvolvedPartiesOrAbort(transaction, updater, config);
        return processTransactionWithParties(
                transaction, updater, context, tracer, config, opsDurationCounter, parties);
    }

    private HederaEvmTransactionResult processTransactionWithParties(
            @NonNull final HederaEvmTransaction transaction,
            @NonNull final HederaWorldUpdater updater,
            @NonNull final HederaEvmContext context,
            @NonNull final ActionSidecarContentTracer tracer,
            @NonNull final Configuration config,
            @NonNull final OpsDurationCounter opsDurationCounter,
            @NonNull final InvolvedParties parties) {
        final var gasCharges =
                gasCharging.chargeForGas(parties.sender(), parties.relayer(), context, updater, transaction);
        final var initialFrame = frameBuilder.buildInitialFrameWith(
                transaction,
                updater,
                context,
                config,
                opsDurationCounter,
                featureFlags,
                parties.sender().getAddress(),
                parties.receiverAddress(),
                gasCharges.intrinsicGas());

        // Compute the result of running the frame to completion
        final var result = frameRunner.runToCompletion(
                transaction.gasLimit(), parties.senderId(), initialFrame, tracer, messageCall, contractCreation);

        // Maybe refund some of the charged fees before committing
        gasCharging.maybeRefundGiven(
                transaction.unusedGas(result.gasUsed()),
                gasCharges.relayerAllowanceUsed(),
                parties.sender(),
                parties.relayer(),
                context,
                updater);
        initialFrame.getSelfDestructs().forEach(updater::deleteAccount);

        // Tries to commit and return the original result; returns a fees-only result on resource exhaustion
        return safeCommit(result, transaction, updater, context, accessTrackerFor(initialFrame));
    }

    private InvolvedParties computeInvolvedPartiesOrAbort(
            @NonNull final HederaEvmTransaction transaction,
            @NonNull final HederaWorldUpdater updater,
            @NonNull final Configuration config) {
        try {
            return computeInvolvedParties(transaction, updater, config);
        } catch (HandleException e) {
            throw e;
        } catch (Exception e) {
            throw new HandleException(INVALID_TRANSACTION_BODY);
        }
    }

    private HederaEvmTransactionResult safeCommit(
            @NonNull final HederaEvmTransactionResult result,
            @NonNull final HederaEvmTransaction transaction,
            @NonNull final HederaWorldUpdater updater,
            @NonNull final HederaEvmContext context,
            @Nullable final StorageAccessTracker accessTracker) {
        try {
            updater.commit();
            if (result.isSuccess()) {
                final var txStorageUsage = txStorageUsageFrom((ProxyWorldUpdater) updater, accessTracker, true);
                if (txStorageUsage != null) {
                    return result.withTxStorageUsage(txStorageUsage);
                }
            }
            return result;
        } catch (ResourceExhaustedException e) {
            updater.revert();
            final var sender = updater.getHederaAccount(transaction.senderId());
            return resourceExhaustionFrom(
                    requireNonNull(sender).hederaId(), transaction.gasLimit(), context.gasPrice(), e.getStatus());
        }
    }

    /**
     * Given an input {@link HederaEvmTransaction}, the {@link HederaWorldUpdater} for the transaction, and the
     * current node {@link Configuration}, sets up the transaction and returns the three "involved parties":
     * <ol>
     *     <li>The sender account.</li>
     *     <li>The (possibly missing) relayer account.</li>
     *     <li>The "to" address receiving the top-level call.</li>
     * </ol>
     *
     * <p>Note that if the transaction is a {@code CONTRACT_CREATION}, setup includes calling either
     * {@link HederaWorldUpdater#setupTopLevelCreate(ContractCreateTransactionBody)} or
     * {@link HederaWorldUpdater#setupAliasedTopLevelCreate(ContractCreateTransactionBody, Address)}
     *
     * @param transaction the transaction to set up
     * @param updater the updater for the transaction
     * @param config the current node configuration
     * @return the involved parties determined while setting up the transaction
     */
    private InvolvedParties computeInvolvedParties(
            @NonNull final HederaEvmTransaction transaction,
            @NonNull final HederaWorldUpdater updater,
            @NonNull final Configuration config) {
        final var sender = updater.getHederaAccount(transaction.senderId());
        validateTrue(sender != null, INVALID_ACCOUNT_ID);
        HederaEvmAccount relayer = null;
        if (transaction.isEthereumTransaction()) {
            relayer = updater.getHederaAccount(requireNonNull(transaction.relayerId()));
            validateTrue(relayer != null, INVALID_ACCOUNT_ID);
        }
        final InvolvedParties parties;
        if (transaction.isCreate()) {
            final Address to;
            final var op = requireNonNull(transaction.hapiCreation());
            if (transaction.isEthereumTransaction()) {
                to = Address.contractAddress(sender.getAddress(), sender.getNonce());
                updater.setupAliasedTopLevelCreate(sponsorCustomizedCreation(op, sender.toNativeAccount()), to);
            } else {
                to = updater.setupTopLevelCreate(op);
            }
            parties = new InvolvedParties(sender, relayer, to);
        } else {
            final var to = updater.getHederaAccount(transaction.contractIdOrThrow());
            if (contractNotRequired(to, config)) {
                parties = partiesWhenContractNotRequired(to, sender, relayer, transaction, updater, config);
            } else {
                parties = partiesWhenContractRequired(to, sender, relayer, transaction, updater, config);
            }
        }
        if (transaction.isEthereumTransaction()) {
            validateTrue(transaction.nonce() == parties.sender().getNonce(), WRONG_NONCE);
        }
        return parties;
    }

    /**
     * Returns whether the given account facade is required to exist.  This only applies to account and contract facade
     * as tokens and schedule txn facades are not required to exist.
     *
     * @param to descendant of {@link HederaEvmAccount} that is the target of this call
     * @param config the current node configuration
     * @return whether the contract is not required to exist.
     */
    private boolean contractNotRequired(@Nullable final HederaEvmAccount to, @NonNull final Configuration config) {
        final var maybeGrandfatheredNumber = (to == null || to.isTokenFacade() || to.isScheduleTxnFacade())
                ? null
                : to.hederaId().accountNumOrThrow();
        return featureFlags.isAllowCallsToNonContractAccountsEnabled(
                config.getConfigData(ContractsConfig.class), maybeGrandfatheredNumber);
    }

    private InvolvedParties partiesWhenContractRequired(
            @Nullable final HederaEvmAccount to,
            @NonNull final HederaEvmAccount sender,
            @Nullable final HederaEvmAccount relayer,
            @NonNull final HederaEvmTransaction transaction,
            @NonNull final HederaWorldUpdater updater,
            @NonNull final Configuration config) {
        final InvolvedParties parties;
        if (maybeLazyCreate(transaction, to, config)) {
            // Presumably these checks _could_ be done later as part of the message
            // call, but historically we have failed fast when they do not pass
            validateTrue(transaction.hasValue(), INVALID_CONTRACT_ID);
            final var alias = transaction.contractIdOrThrow().evmAddressOrThrow();
            validateTrue(isEvmAddress(alias), INVALID_CONTRACT_ID);
            parties = new InvolvedParties(sender, relayer, pbjToBesuAddress(alias));
            updater.setupTopLevelLazyCreate(requireNonNull(parties.receiverAddress));
        } else {
            validateTrue(to != null, INVALID_CONTRACT_ID);
            parties = new InvolvedParties(sender, relayer, requireNonNull(to).getAddress());
        }
        return parties;
    }

    private InvolvedParties partiesWhenContractNotRequired(
            @Nullable final HederaEvmAccount to,
            @NonNull final HederaEvmAccount sender,
            @Nullable final HederaEvmAccount relayer,
            @NonNull final HederaEvmTransaction transaction,
            @NonNull final HederaWorldUpdater updater,
            @NonNull final Configuration config) {
        final InvolvedParties parties;
        if (maybeLazyCreate(transaction, to, config)) {
            // Only set up the lazy creation if the transaction has a value and a valid alias
            final var alias = transaction.contractIdOrThrow().evmAddress();
            if (transaction.hasValue() && alias != null) {
                parties = new InvolvedParties(sender, relayer, pbjToBesuAddress(alias));
                updater.setupTopLevelLazyCreate(requireNonNull(parties.receiverAddress));
            } else {
                updater.setContractNotRequired();
                parties = new InvolvedParties(
                        sender,
                        relayer,
                        contractIDToBesuAddress(updater.entityIdFactory(), transaction.contractIdOrThrow()));
            }
        } else {
            updater.setContractNotRequired();
            parties = new InvolvedParties(
                    sender,
                    relayer,
                    to != null
                            ? to.getAddress()
                            : contractIDToBesuAddress(updater.entityIdFactory(), transaction.contractIdOrThrow()));
        }
        return parties;
    }

    private boolean maybeLazyCreate(
            @NonNull final HederaEvmTransaction transaction,
            @Nullable final HederaEvmAccount to,
            @NonNull final Configuration config) {
        return to == null && transaction.isEthereumTransaction() && messageCall.isImplicitCreationEnabled();
    }
}
