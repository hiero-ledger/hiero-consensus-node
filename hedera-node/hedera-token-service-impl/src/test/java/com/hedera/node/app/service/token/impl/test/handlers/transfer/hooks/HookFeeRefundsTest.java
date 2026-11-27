// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer.hooks;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HookFeeRefunds.unattemptedHookCallsFeeTinycents;
import static org.hiero.hapi.support.fees.Extra.ACCOUNTS;
import static org.hiero.hapi.support.fees.Extra.GAS;
import static org.hiero.hapi.support.fees.Extra.HOOK_EXECUTION;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.EvmHookCall;
import com.hedera.hapi.node.base.HookCall;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HookCalls;
import com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HookContext;
import com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HookInvocation;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.ExtraFeeDefinition;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;
import org.hiero.hapi.support.fees.ServiceFeeSchedule;
import org.junit.jupiter.api.Test;

class HookFeeRefundsTest {
    private static final long HOOK_EXECUTION_FEE = 50_000_000L;
    private static final long GAS_FEE = 852L;
    private static final long MAX_GAS = 15_000_000L;
    private static final long PRE_ONLY_GAS = 10_000L;
    private static final long PRE_POST_GAS = 25_000L;
    private static final AccountID PRE_ONLY_OWNER =
            AccountID.newBuilder().accountNum(1001L).build();
    private static final AccountID PRE_POST_OWNER =
            AccountID.newBuilder().accountNum(1002L).build();

    // One pre-only hook and one pre-post hook: three invocations
    private static final CryptoTransferTransactionBody OP = transfer(PRE_ONLY_GAS, PRE_POST_GAS);
    private static final HookCalls HOOK_CALLS = hookCalls(PRE_ONLY_GAS, PRE_POST_GAS);

    @Test
    void failureBeforeHookCallsWereBuiltRefundsEveryInvocationAndItsGas() {
        final long refund = unattemptedHookCallsFeeTinycents(schedule(false, 0, 0), false, OP, null, 0, MAX_GAS);

        assertEquals(3 * HOOK_EXECUTION_FEE + (PRE_ONLY_GAS + 2 * PRE_POST_GAS) * GAS_FEE, refund);
    }

    @Test
    void failureBeforeAnyHookRanRefundsEveryInvocationAndItsGas() {
        final long refund = unattemptedHookCallsFeeTinycents(schedule(false, 0, 0), false, OP, HOOK_CALLS, 0, MAX_GAS);

        assertEquals(3 * HOOK_EXECUTION_FEE + (PRE_ONLY_GAS + 2 * PRE_POST_GAS) * GAS_FEE, refund);
    }

    @Test
    void failureBetweenPreAndPostCallsRefundsOnlyThePostCall() {
        final long refund = unattemptedHookCallsFeeTinycents(schedule(false, 0, 0), false, OP, HOOK_CALLS, 2, MAX_GAS);

        assertEquals(HOOK_EXECUTION_FEE + PRE_POST_GAS * GAS_FEE, refund);
    }

    @Test
    void nothingIsRefundedWhenEveryCallWasAttempted() {
        final long refund = unattemptedHookCallsFeeTinycents(schedule(false, 0, 0), false, OP, HOOK_CALLS, 3, MAX_GAS);

        assertEquals(0L, refund);
    }

    @Test
    void nothingIsRefundedWhenFeesAreFree() {
        final long refund = unattemptedHookCallsFeeTinycents(schedule(false, 0, 0), true, OP, HOOK_CALLS, 1, MAX_GAS);

        assertEquals(0L, refund);
    }

    @Test
    void nothingIsRefundedWhenCryptoTransferIsFreeInTheSchedule() {
        final long refund = unattemptedHookCallsFeeTinycents(schedule(true, 0, 0), false, OP, HOOK_CALLS, 1, MAX_GAS);

        assertEquals(0L, refund);
    }

    @Test
    void nothingIsRefundedWhenTheScheduleBillsNoHookExtras() {
        final var schedule = FeeSchedule.newBuilder()
                .extras(extra(ACCOUNTS, 1L))
                .services(ServiceFeeSchedule.newBuilder()
                        .name("Crypto")
                        .schedule(ServiceFeeDefinition.newBuilder()
                                .name(CRYPTO_TRANSFER)
                                .extras(ExtraFeeReference.newBuilder()
                                        .name(ACCOUNTS)
                                        .includedCount(2)
                                        .build())
                                .build())
                        .build())
                .build();

        assertEquals(0L, unattemptedHookCallsFeeTinycents(schedule, false, OP, null, 0, MAX_GAS));
    }

    @Test
    void includedUnitsAreNeverRefunded() {
        // Two invocations and 40,000 gas are included, so only one invocation and 20,000 gas were billed
        final var schedule = schedule(false, 2, 40_000);

        assertEquals(
                HOOK_EXECUTION_FEE + 20_000L * GAS_FEE,
                unattemptedHookCallsFeeTinycents(schedule, false, OP, HOOK_CALLS, 0, MAX_GAS));
        // After two calls (35,000 gas) all billed invocations remain unused, but only 20,000 billed gas
        assertEquals(
                HOOK_EXECUTION_FEE + 20_000L * GAS_FEE,
                unattemptedHookCallsFeeTinycents(schedule, false, OP, HOOK_CALLS, 2, MAX_GAS));
        assertEquals(0L, unattemptedHookCallsFeeTinycents(schedule, false, OP, HOOK_CALLS, 3, MAX_GAS));
    }

    @Test
    void gasLimitsAreClampedAsWhenCharged() {
        final long hugeGas = 2 * MAX_GAS;
        final var op = transfer(PRE_ONLY_GAS, hugeGas);
        final var hookCalls = hookCalls(PRE_ONLY_GAS, hugeGas);

        assertEquals(
                HOOK_EXECUTION_FEE + MAX_GAS * GAS_FEE,
                unattemptedHookCallsFeeTinycents(schedule(false, 0, 0), false, op, hookCalls, 2, MAX_GAS));
    }

    private static FeeSchedule schedule(final boolean free, final int includedCalls, final int includedGas) {
        return FeeSchedule.newBuilder()
                .extras(extra(HOOK_EXECUTION, HOOK_EXECUTION_FEE), extra(GAS, GAS_FEE))
                .services(ServiceFeeSchedule.newBuilder()
                        .name("Crypto")
                        .schedule(ServiceFeeDefinition.newBuilder()
                                .name(CRYPTO_TRANSFER)
                                .free(free)
                                .extras(
                                        ExtraFeeReference.newBuilder()
                                                .name(HOOK_EXECUTION)
                                                .includedCount(includedCalls)
                                                .build(),
                                        ExtraFeeReference.newBuilder()
                                                .name(GAS)
                                                .includedCount(includedGas)
                                                .build())
                                .build())
                        .build())
                .build();
    }

    private static ExtraFeeDefinition extra(final Extra name, final long fee) {
        return ExtraFeeDefinition.newBuilder().name(name).fee(fee).build();
    }

    private static CryptoTransferTransactionBody transfer(final long preOnlyGas, final long prePostGas) {
        return CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(
                                AccountAmount.newBuilder()
                                        .accountID(PRE_ONLY_OWNER)
                                        .amount(-1)
                                        .preTxAllowanceHook(hookCall(preOnlyGas))
                                        .build(),
                                AccountAmount.newBuilder()
                                        .accountID(PRE_POST_OWNER)
                                        .amount(1)
                                        .prePostTxAllowanceHook(hookCall(prePostGas))
                                        .build())
                        .build())
                .build();
    }

    private static HookCall hookCall(final long gasLimit) {
        return HookCall.newBuilder()
                .hookId(1L)
                .evmHookCall(EvmHookCall.newBuilder().gasLimit(gasLimit).build())
                .build();
    }

    private static HookCalls hookCalls(final long preOnlyGas, final long prePostGas) {
        return new HookCalls(
                new HookContext(null, "", 0L),
                List.of(new HookInvocation(PRE_ONLY_OWNER, 1L, null, Bytes.EMPTY, preOnlyGas)),
                List.of(new HookInvocation(PRE_POST_OWNER, 1L, null, Bytes.EMPTY, prePostGas)));
    }
}
