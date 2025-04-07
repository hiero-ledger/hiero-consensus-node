// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.token.api.FeeStreamBuilder;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.LongConsumer;
import java.util.function.ObjLongConsumer;

/**
 * Accumulates fees for a given transaction. They can either be charged to a payer account, ore refunded to a receiver
 * account.
 */
public class FeeAccumulator {
    private final TokenServiceApi tokenApi;
    private final FeeStreamBuilder recordBuilder;
    private final LongConsumer onNodeFee;

    /**
     * Creates a new instance of {@link FeeAccumulator}.
     *
     * @param tokenApi the {@link TokenServiceApi} to use to charge and refund fees.
     * @param recordBuilder the {@link FeeStreamBuilder} to record any changes
     * @param stack the {@link SavepointStackImpl} to use to manage savepoints
     */
    public FeeAccumulator(
            @NonNull final TokenServiceApi tokenApi,
            @NonNull final FeeStreamBuilder recordBuilder,
            @NonNull final SavepointStackImpl stack) {
        this.tokenApi = requireNonNull(tokenApi);
        this.recordBuilder = requireNonNull(recordBuilder);
        this.onNodeFee = amount -> stack.peek().trackCollectedNodeFee(amount);
    }

    /**
     * Charges the given network fee to the given payer account.
     *
     * @param payer The account to charge the fees to
     * @param networkFee The network fee to charge
     * @param cb if not null, a callback to receive the fee disbursements
     * @return true if the full fee was charged
     */
    public boolean chargeNetworkFee(
            @NonNull final AccountID payer, final long networkFee, @Nullable final ObjLongConsumer<AccountID> cb) {
        requireNonNull(payer);
        return tokenApi.chargeNetworkFee(payer, networkFee, recordBuilder, cb);
    }

    /**
     * Charges the given fees to the given payer account, distributing the network and service fees among the
     * appropriate collection accounts; and the node fee (if any) to the given node account.
     *
     * @param payer The account to charge the fees to
     * @param nodeAccount The node account to receive the node fee
     * @param fees The fees to charge
     * @param cb if not null, a callback to receive the fee disbursements
     */
    public void chargeFees(
            @NonNull AccountID payer,
            @NonNull final AccountID nodeAccount,
            @NonNull Fees fees,
            @Nullable final ObjLongConsumer<AccountID> cb) {
        requireNonNull(payer);
        requireNonNull(nodeAccount);
        requireNonNull(fees);
        tokenApi.chargeFees(payer, nodeAccount, fees, recordBuilder, cb, onNodeFee);
    }

    /**
     * Refunds the given fees to the receiver account.
     *
     * @param receiver The account to refund the fees to.
     * @param fees The fees to refund.
     */
    public void refund(@NonNull AccountID receiver, @NonNull Fees fees) {
        tokenApi.refundFees(receiver, fees, recordBuilder);
    }
}
