// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.AMOUNT_EXCEEDS_ALLOWANCE;
import static org.hiero.hapi.support.fees.Extra.GAS;
import static org.hiero.hapi.support.fees.Extra.HOOK_EXECUTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.withSettings;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.EvmHookCall;
import com.hedera.hapi.node.base.HookCall;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferContextImpl;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferExecutor;
import com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HookCallsFactory;
import com.hedera.node.app.service.token.impl.validators.CryptoTransferValidator;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.hiero.hapi.support.fees.ExtraFeeDefinition;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;
import org.hiero.hapi.support.fees.ServiceFeeSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that a transfer with hook calls that fails refunds, on rollback, what was charged for the hook calls that
 * never ran, and only what was charged.
 */
@ExtendWith(MockitoExtension.class)
class TransferExecutorHookFeeRefundTest {
    private static final AccountID PAYER =
            AccountID.newBuilder().accountNum(1234L).build();
    private static final AccountID OWNER =
            AccountID.newBuilder().accountNum(1001L).build();
    private static final AccountID RECEIVER =
            AccountID.newBuilder().accountNum(1002L).build();
    private static final long HOOK_EXECUTION_FEE = 50_000_000L;
    private static final long GAS_FEE = 852L;
    private static final long PRE_ONLY_GAS = 10_000L;
    private static final long PRE_POST_GAS = 25_000L;
    private static final long TINYCENTS_PER_TINYBAR = 12L;

    @Mock
    private CryptoTransferValidator validator;

    @Mock
    private HookCallsFactory hookCallsFactory;

    @Mock
    private EntityIdFactory entityIdFactory;

    @Mock
    private TransferContextImpl transferContext;

    @Mock
    private FeeCharging.Context feeChargingContext;

    private HandleContext context;
    private Subject subject;

    @BeforeEach
    void setUp() {
        context = mock(HandleContext.class, withSettings().extraInterfaces(FeeContext.class));
        subject = new Subject(validator, hookCallsFactory, entityIdFactory);
    }

    @Test
    void failureBeforeAnyHookRunsRefundsEveryHookCall() {
        givenRefundContext(false);
        doThrow(new HandleException(AMOUNT_EXCEEDS_ALLOWANCE))
                .when(transferContext)
                .validateHbarAllowances();

        final var e = assertThrows(HandleException.class, () -> subject.execute(withHooks(), transferContext, context));
        e.maybeReplay(feeChargingContext, context);

        assertEquals(AMOUNT_EXCEEDS_ALLOWANCE, e.getStatus());
        final long chargedForHooks = 3 * HOOK_EXECUTION_FEE + (PRE_ONLY_GAS + 2 * PRE_POST_GAS) * GAS_FEE;
        verify(feeChargingContext).refund(PAYER, new Fees(0, 0, chargedForHooks / TINYCENTS_PER_TINYBAR));
        verifyNoInteractions(hookCallsFactory);
    }

    @Test
    void nothingIsRefundedWhenFeesAreFree() {
        givenRefundContext(true);
        doThrow(new HandleException(AMOUNT_EXCEEDS_ALLOWANCE))
                .when(transferContext)
                .validateHbarAllowances();

        final var e = assertThrows(HandleException.class, () -> subject.execute(withHooks(), transferContext, context));
        e.maybeReplay(feeChargingContext, context);

        assertEquals(AMOUNT_EXCEEDS_ALLOWANCE, e.getStatus());
        verifyNoInteractions(feeChargingContext);
    }

    @Test
    void failureOfTransferWithoutHooksIsRethrownAsIs() {
        final var failure = new HandleException(AMOUNT_EXCEEDS_ALLOWANCE);
        doThrow(failure).when(transferContext).validateHbarAllowances();

        final var e =
                assertThrows(HandleException.class, () -> subject.execute(withoutHooks(), transferContext, context));

        assertSame(failure, e);
    }

    private void givenRefundContext(final boolean feesAreFree) {
        given(context.payer()).willReturn(PAYER);
        given(context.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("fees.simpleFeesAreFree", feesAreFree)
                        .getOrCreateConfig());
        given(context.simpleFeesSchedule()).willReturn(schedule());
        if (!feesAreFree) {
            given(((FeeContext) context).tinybarsFromTinycents(anyLong()))
                    .willAnswer(invocation -> invocation.<Long>getArgument(0) / TINYCENTS_PER_TINYBAR);
        }
    }

    private static TransactionBody withHooks() {
        return transfer(
                AccountAmount.newBuilder()
                        .accountID(OWNER)
                        .amount(-1)
                        .preTxAllowanceHook(hookCall(PRE_ONLY_GAS))
                        .build(),
                AccountAmount.newBuilder()
                        .accountID(RECEIVER)
                        .amount(1)
                        .prePostTxAllowanceHook(hookCall(PRE_POST_GAS))
                        .build());
    }

    private static TransactionBody withoutHooks() {
        return transfer(
                AccountAmount.newBuilder().accountID(OWNER).amount(-1).build(),
                AccountAmount.newBuilder().accountID(RECEIVER).amount(1).build());
    }

    private static TransactionBody transfer(final AccountAmount... adjustments) {
        return TransactionBody.newBuilder()
                .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                        .transfers(TransferList.newBuilder()
                                .accountAmounts(adjustments)
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

    private static FeeSchedule schedule() {
        return FeeSchedule.newBuilder()
                .extras(
                        ExtraFeeDefinition.newBuilder()
                                .name(HOOK_EXECUTION)
                                .fee(HOOK_EXECUTION_FEE)
                                .build(),
                        ExtraFeeDefinition.newBuilder().name(GAS).fee(GAS_FEE).build())
                .services(ServiceFeeSchedule.newBuilder()
                        .name("Crypto")
                        .schedule(ServiceFeeDefinition.newBuilder()
                                .name(CRYPTO_TRANSFER)
                                .extras(
                                        ExtraFeeReference.newBuilder()
                                                .name(HOOK_EXECUTION)
                                                .build(),
                                        ExtraFeeReference.newBuilder().name(GAS).build())
                                .build())
                        .build())
                .build();
    }

    private static class Subject extends TransferExecutor {
        Subject(
                final CryptoTransferValidator validator,
                final HookCallsFactory hookCallsFactory,
                final EntityIdFactory entityIdFactory) {
            super(validator, hookCallsFactory, entityIdFactory);
        }

        void execute(
                final TransactionBody txn, final TransferContextImpl transferContext, final HandleContext context) {
            executeCryptoTransfer(txn, transferContext, context, null);
        }
    }
}
