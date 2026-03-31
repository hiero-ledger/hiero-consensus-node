// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.utils;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EthereumTransactionRollbackHandler implements HandleException.OnRollback {

    private final CallOutcome outcome;
    private final List<HederaOperations.GasChargingEvent> gasChargingEvents;
    private final HandleContext handleContext;

    public EthereumTransactionRollbackHandler(
            @NonNull CallOutcome outcome,
            @NonNull List<HederaOperations.GasChargingEvent> gasChargingEvents,
            @NonNull HandleContext handleContext) {
        this.outcome = outcome;
        this.gasChargingEvents = gasChargingEvents;
        this.handleContext = handleContext;
    }

    @Override
    public void replay(
            @NonNull FeeCharging.Context feeChargingContext, @NonNull HandleException.ChildDispatch dispatch) {
        // Replay fee charges
        replayGasChargingIn(feeChargingContext);
    }

    private void replayGasChargingIn(@NonNull final FeeCharging.Context feeChargingContext) {
        final Map<AccountID, Long> netCharges = new LinkedHashMap<>();
        for (final var event : gasChargingEvents) {
            if (event.action() == HandleHederaOperations.GasChargingAction.CHARGE) {
                netCharges.merge(event.accountId(), event.amount(), Long::sum);
                if (event.withNonceIncrement()) {
                    final var tokenServiceApi = handleContext.storeFactory().serviceApi(TokenServiceApi.class);
                    tokenServiceApi.incrementSenderNonce(event.accountId());
                }
            } else {
                netCharges.merge(event.accountId(), -event.amount(), Long::sum);
            }
        }
        netCharges.forEach((payerId, amount) -> feeChargingContext.charge(payerId, new Fees(0, amount, 0), null));
    }
}
