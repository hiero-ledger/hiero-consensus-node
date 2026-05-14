// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.utils;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.state.HederaEvmAccount;
import com.hedera.node.app.service.contract.impl.state.RootProxyWorldUpdater;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EthereumTransactionRollbackHandler implements HandleException.OnRollback {

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
        replayCodeDelegations();
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

    private void replayCodeDelegations() {
        if (outcome.codeDelegationResult() != null) {
            long remainingLazyCreationGas = outcome.codeDelegationResult().gasInitiallyAvailableForLazyCreations();
            for (final var delegation : outcome.codeDelegationResult().validDelegations()) {
                /* Note: it's important that we reevaluate whether the given delegation entry creates or updates
                    an exiting account. It might happen that an account that existed on the main flow no longer
                    exists in the revert flow (e.g. if a preceding atomic batch transaction that created it was
                    reverted).
                */
                final var authorityAccount = rootProxyWorldUpdater.getAccount(delegation.authorityAddress());
                if (authorityAccount != null) {
                    // Result purposely ignored - there's nothing else we can do if it fails
                    rootProxyWorldUpdater.setAccountCodeDelegation(
                            ((HederaEvmAccount) authorityAccount).hederaId(), delegation.delegationTarget());
                    authorityAccount.incrementNonce();
                } else {
                    final var lazyCreationCost =
                            rootProxyWorldUpdater.lazyCreationCostInGas(delegation.authorityAddress());
                    if (remainingLazyCreationGas >= lazyCreationCost) {
                        // Result purposely ignored - there's nothing else we can do if it fails
                        rootProxyWorldUpdater.createAccountWithKeyAndCodeDelegation(
                                delegation.authorityAddress(),
                                delegation.authorityEcdsaPublicKey().toArray(),
                                delegation.delegationTarget());
                        final var newAccount = rootProxyWorldUpdater.getAccount(delegation.authorityAddress());
                        if (newAccount != null) {
                            newAccount.incrementNonce();
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
