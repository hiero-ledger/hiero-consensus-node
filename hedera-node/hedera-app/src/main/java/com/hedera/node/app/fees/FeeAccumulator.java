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
import java.util.HashMap;
import java.util.Map;
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

    @Nullable
    private AccountID nodeAccountId;

    @Nullable
    private AccountID primaryPayerId;

    private long primaryRefundableNodeFee;
    private long primaryRefundableNonNodeFee;

    @Nullable
    private Map<AccountID, RefundableFees> overflowRefundableFees;

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
        requireNonNegative(networkFee);
        final var chargedFees = tokenApi.chargeFee(payer, networkFee, (StreamBuilder) feeStreamBuilder, cb);
        credit(payer, 0, chargedFees.totalFee());
        return chargedFees;
    }

    /**
     * Refunds the given network fee to the given payer account.
     *
     * @param payer The account to refund the fees to
     * @param networkFee The network fee to refund
     */
    public void refundFee(@NonNull final AccountID payer, final long networkFee) {
        requireNonNull(payer);
        requireNonNegative(networkFee);
        requireRefundable(payer, 0, networkFee);
        tokenApi.refundFee(payer, networkFee, feeStreamBuilder);
        debit(payer, 0, networkFee);
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
        validateNodeAccount(nodeAccount);
        final var chargedFees = tokenApi.chargeFees(payer, nodeAccount, fees, feeStreamBuilder, cb, onNodeFeeCharged);
        rememberNodeAccount(nodeAccount);
        credit(payer, chargedFees.nodeFee(), chargedFees.totalWithoutNodeFee());
        return chargedFees;
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
        validateNodeAccount(nodeAccountId);
        requireRefundable(payerId, fees.nodeFee(), fees.totalWithoutNodeFee());
        tokenApi.refundFees(payerId, nodeAccountId, fees, feeStreamBuilder, onNodeFeeRefunded);
        rememberNodeAccount(nodeAccountId);
        debit(payerId, fees.nodeFee(), fees.totalWithoutNodeFee());
    }

    /**
     * Resets the internal refundable fee ledger after this dispatch's stack has been rolled back.
     */
    public void resetRefundableFees() {
        nodeAccountId = null;
        primaryPayerId = null;
        primaryRefundableNodeFee = 0;
        primaryRefundableNonNodeFee = 0;
        overflowRefundableFees = null;
    }

    private void validateNodeAccount(@NonNull final AccountID nodeAccountId) {
        if (this.nodeAccountId != null
                && this.nodeAccountId != nodeAccountId
                && !this.nodeAccountId.equals(nodeAccountId)) {
            throw new IllegalArgumentException("FeeAccumulator cannot change node account during its lifecycle");
        }
    }

    private void rememberNodeAccount(@NonNull final AccountID nodeAccountId) {
        if (this.nodeAccountId == null) {
            this.nodeAccountId = nodeAccountId;
        }
    }

    private void requireNonNegative(final long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Cannot charge or refund negative fees " + amount);
        }
    }

    private void credit(@NonNull final AccountID payerId, final long nodeFee, final long nonNodeFee) {
        if (nodeFee == 0 && nonNodeFee == 0) {
            return;
        }
        if (primaryPayerId == null) {
            primaryPayerId = payerId;
            primaryRefundableNodeFee = nodeFee;
            primaryRefundableNonNodeFee = nonNodeFee;
        } else if (isPrimaryPayer(payerId)) {
            primaryRefundableNodeFee = Math.addExact(primaryRefundableNodeFee, nodeFee);
            primaryRefundableNonNodeFee = Math.addExact(primaryRefundableNonNodeFee, nonNodeFee);
        } else {
            overflowRefundableFees()
                    .computeIfAbsent(payerId, ignored -> new RefundableFees())
                    .credit(nodeFee, nonNodeFee);
        }
    }

    private void requireRefundable(@NonNull final AccountID payerId, final long nodeFee, final long nonNodeFee) {
        if (nodeFee == 0 && nonNodeFee == 0) {
            return;
        }
        if (primaryPayerId != null && isPrimaryPayer(payerId)) {
            requireRefundable(payerId, nodeFee, nonNodeFee, primaryRefundableNodeFee, primaryRefundableNonNodeFee);
        } else {
            final var refundableFees = overflowRefundableFees == null ? null : overflowRefundableFees.get(payerId);
            final long refundableNodeFee = refundableFees == null ? 0 : refundableFees.nodeFee;
            final long refundableNonNodeFee = refundableFees == null ? 0 : refundableFees.nonNodeFee;
            requireRefundable(payerId, nodeFee, nonNodeFee, refundableNodeFee, refundableNonNodeFee);
        }
    }

    private void requireRefundable(
            @NonNull final AccountID payerId,
            final long nodeFee,
            final long nonNodeFee,
            final long refundableNodeFee,
            final long refundableNonNodeFee) {
        if (nodeFee > refundableNodeFee || nonNodeFee > refundableNonNodeFee) {
            throw new IllegalArgumentException(("Cannot refund more fees to %s than were charged; "
                            + "requested node/non-node fees %d/%d but only %d/%d remain refundable")
                    .formatted(payerId, nodeFee, nonNodeFee, refundableNodeFee, refundableNonNodeFee));
        }
    }

    private void debit(@NonNull final AccountID payerId, final long nodeFee, final long nonNodeFee) {
        if (nodeFee == 0 && nonNodeFee == 0) {
            return;
        }
        if (primaryPayerId != null && isPrimaryPayer(payerId)) {
            primaryRefundableNodeFee -= nodeFee;
            primaryRefundableNonNodeFee -= nonNodeFee;
        } else {
            final var refundableFees = requireNonNull(overflowRefundableFees).get(payerId);
            refundableFees.nodeFee -= nodeFee;
            refundableFees.nonNodeFee -= nonNodeFee;
            if (refundableFees.nodeFee == 0 && refundableFees.nonNodeFee == 0) {
                overflowRefundableFees.remove(payerId);
            }
        }
    }

    private boolean isPrimaryPayer(@NonNull final AccountID payerId) {
        final var primaryPayerId = requireNonNull(this.primaryPayerId);
        return primaryPayerId == payerId || primaryPayerId.equals(payerId);
    }

    private Map<AccountID, RefundableFees> overflowRefundableFees() {
        if (overflowRefundableFees == null) {
            overflowRefundableFees = new HashMap<>();
        }
        return overflowRefundableFees;
    }

    private static final class RefundableFees {
        private long nodeFee;
        private long nonNodeFee;

        private void credit(final long nodeFee, final long nonNodeFee) {
            this.nodeFee = Math.addExact(this.nodeFee, nodeFee);
            this.nonNodeFee = Math.addExact(this.nonNodeFee, nonNodeFee);
        }
    }
}
