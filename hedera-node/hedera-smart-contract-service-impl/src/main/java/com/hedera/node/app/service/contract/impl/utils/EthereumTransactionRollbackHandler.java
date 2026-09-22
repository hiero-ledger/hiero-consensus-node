// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.utils;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.synthAccountCreationWithKeyAndCodeDelegation;
import static com.hedera.node.app.spi.workflows.DispatchOptions.stepDispatch;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.state.HederaEntityResolver;
import com.hedera.node.app.service.contract.impl.state.RootProxyWorldUpdater;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.service.token.records.CryptoCreateStreamBuilder;
import com.hedera.node.app.service.token.records.CryptoUpdateStreamBuilder;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class EthereumTransactionRollbackHandler implements HandleException.OnRollback {
    private static final Logger log = LogManager.getLogger(EthereumTransactionRollbackHandler.class);

    private final CallOutcome outcome;
    private final List<HederaOperations.GasChargingEvent> gasChargingEvents;
    private final RootProxyWorldUpdater rootProxyWorldUpdater;

    public EthereumTransactionRollbackHandler(
            @NonNull CallOutcome outcome,
            @NonNull List<HederaOperations.GasChargingEvent> gasChargingEvents,
            @NonNull RootProxyWorldUpdater rootProxyWorldUpdater) {
        this.outcome = outcome;
        this.gasChargingEvents = gasChargingEvents;
        this.rootProxyWorldUpdater = rootProxyWorldUpdater;
    }

    @Override
    public void replay(@NonNull FeeCharging.Context feeChargingContext, @NonNull HandleContext handleContext) {
        // Replay fee charges
        replayGasChargingIn(feeChargingContext, handleContext);

        // Replay code delegations
        replayCodeDelegations(handleContext);
    }

    private void replayGasChargingIn(
            @NonNull final FeeCharging.Context feeChargingContext, HandleContext handleContext) {
        final var tokenServiceApi = handleContext.storeFactory().serviceApi(TokenServiceApi.class);
        final Map<AccountID, Long> netCharges = new LinkedHashMap<>();
        for (final var event : gasChargingEvents) {
            if (event.action() == HandleHederaOperations.GasChargingAction.CHARGE) {
                netCharges.merge(event.accountId(), event.amount(), Long::sum);
                if (event.withNonceIncrement()) {
                    tokenServiceApi.incrementSenderNonce(event.accountId());
                }
            } else {
                netCharges.merge(event.accountId(), -event.amount(), Long::sum);
            }
        }
        netCharges.forEach((payerId, amount) -> {
            feeChargingContext.charge(payerId, new Fees(0, amount, 0), null);
        });
    }

    /**
     * Best effort code delegations replay for reverted transactions.
     */
    private void replayCodeDelegations(HandleContext handleContext) {
        final var nativeOps =
                new HandleHederaNativeOperations(handleContext, null, rootProxyWorldUpdater.entityIdFactory());
        final var entityResolver = new HederaEntityResolver(nativeOps);
        final var tokenServiceApi = handleContext.storeFactory().serviceApi(TokenServiceApi.class);
        final var unlimitedAutoAssociationsEnabled = handleContext
                .configuration()
                .getConfigData(EntitiesConfig.class)
                .unlimitedAutoAssociationsEnabled();

        final var codeDelegationResult = outcome.codeDelegationResult();
        if (codeDelegationResult != null) {
            // TODO: we should adjust the charged gas amount if any additional lazy creation
            // charges have been applied (see the comment below).
            // This however only applies to atomic batch transactions, and currently (2026-05-12)
            // Ethereum fees are mishandled for reverted batch transactions - refunds are ignored,
            // so we can skip the adjustment here until that's fixed.
            long remainingLazyCreationGas = codeDelegationResult.gasInitiallyAvailableForLazyCreations()
                    - codeDelegationResult.totalLazyCreationGasCharged();
            for (final var delegation : codeDelegationResult.validDelegations()) {
                /* Note: it's important that we reevaluate whether the given delegation entry creates or updates
                    an exiting account. It might happen that an account that existed on the main flow no longer
                    exists in the revert flow (e.g. if a preceding atomic batch transaction that created it was
                    reverted).
                */
                final var entity = entityResolver.resolveEvmAddressToHederaEntity(delegation.authorityAddress());
                if (entity != null) {
                    if (entity instanceof HederaEntityResolver.HederaEntity.AccountEntity(Account account)
                            && account.accountId() != null) {
                        final var cryptoUpdate = CryptoUpdateTransactionBody.newBuilder()
                                .accountIDToUpdate(account.accountId())
                                .delegationAddress(tuweniToPbjBytes(
                                        delegation.delegationTarget().getBytes()))
                                .build();
                        final var dispatchOpts = stepDispatch(
                                handleContext.payer(),
                                TransactionBody.newBuilder()
                                        .cryptoUpdateAccount(cryptoUpdate)
                                        .build(),
                                CryptoUpdateStreamBuilder.class,
                                NOOP_SIGNED_TX_CUSTOMIZER,
                                StreamBuilder.ReversingBehavior.IRREVERSIBLE,
                                new HandleContext.DispatchMetadata(Map.of()));
                        try {
                            handleContext.dispatch(dispatchOpts);
                        } catch (HandleException e) {
                            // We're doing a best effort code delegations replay.
                            // If it fails here (for whatever reason), we don't care.
                        }
                        tokenServiceApi.incrementSenderNonce(account.accountId());
                    } else {
                        // This shouldn't be possible, but let's log just in case.
                        log.warn("""
                               EthereumTransactionRollbackHandler encountered a seemingly valid code delegation
                               signed by the private key matching a non-account entity address.
                               This might indicate a bug elsewhere (see: CodeDelegationProcessor).""");
                    }
                } else {
                    final var lazyCreationCost = delegation.lazyAccountCreationGasPaid()
                            ? 0 /* lazy creation gas was already deducted */
                            : rootProxyWorldUpdater.lazyCreationCostInGas(delegation.authorityAddress());
                    if (remainingLazyCreationGas >= lazyCreationCost) {
                        final var cryptoCreateTxn = synthAccountCreationWithKeyAndCodeDelegation(
                                tuweniToPbjBytes(delegation.authorityAddress().getBytes()),
                                Key.newBuilder()
                                        .ecdsaSecp256k1(Bytes.wrap(delegation
                                                .authorityEcdsaPublicKey()
                                                .toArray()))
                                        .build(),
                                tuweniToPbjBytes(delegation.delegationTarget().getBytes()),
                                unlimitedAutoAssociationsEnabled);
                        final var dispatchOpts = stepDispatch(
                                handleContext.payer(),
                                TransactionBody.newBuilder()
                                        .cryptoCreateAccount(cryptoCreateTxn)
                                        .build(),
                                CryptoCreateStreamBuilder.class,
                                NOOP_SIGNED_TX_CUSTOMIZER,
                                StreamBuilder.ReversingBehavior.IRREVERSIBLE,
                                new HandleContext.DispatchMetadata(Map.of()));
                        try {
                            handleContext.dispatch(dispatchOpts);
                        } catch (HandleException e) {
                            // We're doing a best effort code delegations replay.
                            // If it fails here (for whatever reason), we don't care.
                        }
                        final var maybeNewAccount =
                                entityResolver.resolveEvmAddressToHederaEntity(delegation.authorityAddress());
                        if (maybeNewAccount instanceof HederaEntityResolver.HederaEntity.AccountEntity(Account account)
                                && account.accountId() != null) {
                            tokenServiceApi.incrementSenderNonce(account.accountId());
                        }

                        // We decrement the gas, even if the creation failed - unlikely to
                        // affect users much and better be safe.
                        remainingLazyCreationGas -= lazyCreationCost;
                    }
                }
            }
        }
    }
}
