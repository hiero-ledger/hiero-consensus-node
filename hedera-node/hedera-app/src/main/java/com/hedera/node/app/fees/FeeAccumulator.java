// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.token.api.FeeStreamBuilder;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
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
    private final FeeStreamBuilder feeStreamBuilder;
    private final LongConsumer onNodeFeeCharged;
    private final LongConsumer onNodeFeeRefunded;

    /**
     * Creates a new instance of {@link FeeAccumulator}.
     *
     * @param tokenApi the {@link TokenServiceApi} to use to charge and refund fees.
     * @param feeStreamBuilder the {@link FeeStreamBuilder} to record any changes
     * @param stack the {@link SavepointStackImpl} to use to manage savepoints
     */
    public FeeAccumulator(
            @NonNull final TokenServiceApi tokenApi,
            @NonNull final FeeStreamBuilder feeStreamBuilder,
            @NonNull final SavepointStackImpl stack) {
        this.tokenApi = requireNonNull(tokenApi);
        this.feeStreamBuilder = requireNonNull(feeStreamBuilder);
        this.onNodeFeeCharged = amount -> stack.peek().trackCollectedNodeFee(amount);
        this.onNodeFeeRefunded = amount -> stack.peek().trackRefundedNodeFee(amount);
    }

    /**
     * Charges the given network fee to the given payer account.
     *
     * @param payer The account to charge the fees to
     * @param networkFee The network fee to charge
     * @param cb if not null, a callback to receive the fee disbursements
     * @return the amount of fees charged
     */
    public Fees chargeFee(
            @NonNull final AccountID payer, final long networkFee, @Nullable final ObjLongConsumer<AccountID> cb) {
        requireNonNull(payer);
        return tokenApi.chargeFee(payer, networkFee, (StreamBuilder) feeStreamBuilder, cb);
    }

    /**
     * Refunds the given network fee to the given payer account.
     *
     * @param payer The account to refund the fees to
     * @param networkFee The network fee to refund
     */
    public void refundFee(@NonNull final AccountID payer, final long networkFee) {
        requireNonNull(payer);
        tokenApi.refundFee(payer, networkFee, feeStreamBuilder);
    }

    /**
     * Charges the given fees to the given payer account, distributing the network and service fees among the
     * appropriate collection accounts; and the node fee (if any) to the given node account.
     *
     * @param payer The account to charge the fees to
     * @param nodeAccount The node account to receive the node fee
     * @param fees The fees to charge
     * @param cb if not null, a callback to receive the fee disbursements
     * @return the amount of fees charged
     */
    public Fees chargeFees(
            @NonNull final AccountID payer,
            @NonNull final AccountID nodeAccount,
            @NonNull final Fees fees,
            @Nullable final ObjLongConsumer<AccountID> cb) {
        requireNonNull(payer);
        requireNonNull(nodeAccount);
        requireNonNull(fees);
        return tokenApi.chargeFees(payer, nodeAccount, fees, feeStreamBuilder, cb, onNodeFeeCharged);
    }

    /**
     * Refunds the given fees to the receiver account.
     *
     * @param payerId The account to refund the fees to.
     * @param fees The fees to refund.
     * @param nodeAccountId The node account to refund the fees from.
     */
    public void refundFees(
            @NonNull final AccountID payerId, @NonNull final Fees fees, @NonNull final AccountID nodeAccountId) {
        requireNonNull(payerId);
        requireNonNull(nodeAccountId);
        requireNonNull(fees);
        tokenApi.refundFees(payerId, nodeAccountId, fees, feeStreamBuilder, onNodeFeeRefunded);
    }
}
