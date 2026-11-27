// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer.hooks;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.node.app.hapi.utils.CommonUtils.clampedAdd;
import static com.hedera.node.app.hapi.utils.CommonUtils.clampedMultiply;
import static com.hedera.node.app.service.token.impl.handlers.CryptoTransferHandler.chargeableGasLimit;
import static com.hedera.node.app.service.token.impl.handlers.CryptoTransferHandler.getHookInfo;
import static java.util.Objects.requireNonNull;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupExtraFee;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;
import static org.hiero.hapi.support.fees.Extra.GAS;
import static org.hiero.hapi.support.fees.Extra.HOOK_EXECUTION;

import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.impl.handlers.CryptoTransferHandler.HookInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/**
 * Computes the part of a {@code CryptoTransfer} service fee that paid for hook calls that never ran.
 *
 * <p>The fee calculator charges the {@code HOOK_EXECUTION} and {@code GAS} extras of the {@code CryptoTransfer}
 * service fee for every hook invocation in the transaction body (a pre-post hook counts twice) and its gas limit. The
 * refund is what those two extras cost for the invocations that were never attempted: the extras billed for all
 * invocations minus the extras that would have been billed for the attempted ones alone, both from the same fee
 * schedule. So nothing is refunded when the extras were not charged, because fees are free on the network, the
 * {@code CryptoTransfer} service fee is free, or the schedule bills neither extra.
 */
public final class HookFeeRefunds {
    private HookFeeRefunds() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the hook call fees, in tinycents, charged for the invocations that were never attempted.
     *
     * @param schedule the simple fee schedule the transaction was charged with
     * @param feesAreFree whether simple fees are free on the network
     * @param op the transfer as submitted
     * @param hookCalls the hook calls built for the transfer, or null if none were built before it failed
     * @param numAttemptedHookCalls the number of hook invocations attempted, in dispatch order
     * @param maxGasPerTransaction the gas limit per transaction the charged gas limits were clamped to
     * @return the fee to refund in tinycents, never negative
     */
    public static long unattemptedHookCallsFeeTinycents(
            @NonNull final FeeSchedule schedule,
            final boolean feesAreFree,
            @NonNull final CryptoTransferTransactionBody op,
            @Nullable final HookCalls hookCalls,
            final int numAttemptedHookCalls,
            final long maxGasPerTransaction) {
        requireNonNull(schedule);
        requireNonNull(op);
        if (feesAreFree) {
            return 0L;
        }
        final var serviceFee = lookupServiceFee(schedule, CRYPTO_TRANSFER);
        if (serviceFee == null || serviceFee.free()) {
            return 0L;
        }
        final var charged = getHookInfo(op, maxGasPerTransaction);
        final var attempted = attemptedHookInfo(hookCalls, numAttemptedHookCalls, maxGasPerTransaction);
        return clampedAdd(
                unusedExtraFee(
                        schedule,
                        serviceFee,
                        HOOK_EXECUTION,
                        charged.numHookInvocations(),
                        attempted.numHookInvocations()),
                unusedExtraFee(
                        schedule, serviceFee, GAS, charged.totalGasLimitOfHooks(), attempted.totalGasLimitOfHooks()));
    }

    /**
     * Returns the number and chargeable gas of the hook invocations attempted, which run in the order pre-only
     * hooks, then the pre calls of pre-post hooks, then their post calls.
     */
    private static HookInfo attemptedHookInfo(
            @Nullable final HookCalls hookCalls, final int numAttemptedHookCalls, final long maxGasPerTransaction) {
        if (hookCalls == null || numAttemptedHookCalls <= 0) {
            return HookInfo.NO_HOOKS;
        }
        int remaining = numAttemptedHookCalls;
        long gas = 0L;
        for (final var invocations :
                List.of(hookCalls.preOnlyHooks(), hookCalls.prePostHooks(), hookCalls.prePostHooks())) {
            for (final var invocation : invocations) {
                if (remaining == 0) {
                    return new HookInfo(numAttemptedHookCalls, gas);
                }
                gas = clampedAdd(gas, chargeableGasLimit(invocation.gasLimit(), maxGasPerTransaction));
                remaining--;
            }
        }
        return new HookInfo(numAttemptedHookCalls - remaining, gas);
    }

    /**
     * Returns what the given extra cost for the charged units beyond those used, applying the included count the
     * same way the fee calculator does.
     */
    private static long unusedExtraFee(
            @NonNull final FeeSchedule schedule,
            @NonNull final ServiceFeeDefinition serviceFee,
            @NonNull final Extra extra,
            final long chargedUnits,
            final long usedUnits) {
        for (final var ref : serviceFee.extras()) {
            if (ref.name() == extra) {
                final var definition = lookupExtraFee(schedule, extra);
                if (definition == null) {
                    return 0L;
                }
                final long billedUnits = Math.max(0L, chargedUnits - ref.includedCount());
                final long usedBilledUnits = Math.max(0L, Math.min(usedUnits, chargedUnits) - ref.includedCount());
                return clampedMultiply(definition.fee(), billedUnits - usedBilledUnits);
            }
        }
        return 0L;
    }
}
