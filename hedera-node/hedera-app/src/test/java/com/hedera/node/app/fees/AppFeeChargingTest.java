// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.workflows.SolvencyPreCheck;
import com.hedera.node.app.workflows.handle.dispatch.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AppFeeChargingTest {
    private static final AccountID CREATOR_ID =
            AccountID.newBuilder().accountNum(3L).build();
    private static final AccountID PAYER_ID =
            AccountID.newBuilder().accountNum(1234L).build();
    private static final Account PAYER =
            Account.newBuilder().accountId(PAYER_ID).build();
    private static final Fees FEES = new Fees(1L, 2L, 3L);

    @Mock
    private SolvencyPreCheck solvencyPreCheck;

    @Mock
    private FeeCharging.Context ctx;

    private AppFeeCharging subject;

    @BeforeEach
    void setUp() {
        subject = new AppFeeCharging(solvencyPreCheck);
    }

    @Test
    void refusesToChargeWithoutValidationResult() {
        final var wrongValidation = mock(FeeCharging.Validation.class);

        assertThrows(IllegalArgumentException.class, () -> subject.charge(ctx, wrongValidation, FEES));
    }

    @Test
    void chargesEverythingOnSuccess() {
        final var result = ValidationResult.newSuccess(CREATOR_ID, PAYER);
        given(ctx.category()).willReturn(HandleContext.TransactionCategory.NODE);
        given(ctx.payerId()).willReturn(PAYER_ID);
        given(ctx.nodeAccountId()).willReturn(CREATOR_ID);

        subject.charge(ctx, result, FEES);

        verify(ctx).charge(PAYER_ID, FEES, CREATOR_ID, null);
    }

    @Test
    void waivesServiceFeeIfPayerUnableToAffordSvcComponent() {
        final var result = ValidationResult.newSuccess(CREATOR_ID, PAYER).withoutServiceFee();
        given(ctx.category()).willReturn(HandleContext.TransactionCategory.USER);
        given(ctx.payerId()).willReturn(PAYER_ID);
        given(ctx.nodeAccountId()).willReturn(CREATOR_ID);

        subject.charge(ctx, result, FEES);

        verify(ctx).charge(PAYER_ID, FEES.withoutServiceComponent(), CREATOR_ID, null);
    }

    @Test
    void waivesServiceFeeOnDuplicate() {
        final var result = ValidationResult.newPayerDuplicateError(CREATOR_ID, PAYER);
        given(ctx.category()).willReturn(HandleContext.TransactionCategory.USER);
        given(ctx.payerId()).willReturn(PAYER_ID);
        given(ctx.nodeAccountId()).willReturn(CREATOR_ID);

        subject.charge(ctx, result, FEES);

        verify(ctx).charge(PAYER_ID, FEES.withoutServiceComponent(), CREATOR_ID, null);
    }

    @Test
    void defaultsToSkippingNodeAccountDisbursement() {
        final var result = ValidationResult.newSuccess(CREATOR_ID, PAYER);
        given(ctx.category()).willReturn(HandleContext.TransactionCategory.SCHEDULED);
        given(ctx.payerId()).willReturn(PAYER_ID);

        subject.charge(ctx, result, FEES);

        verify(ctx).charge(PAYER_ID, FEES, null);
    }

    @Test
    void defaultsToNotRefundingNodeAccount() {
        given(ctx.category()).willReturn(HandleContext.TransactionCategory.SCHEDULED);
        given(ctx.payerId()).willReturn(PAYER_ID);

        subject.refund(ctx, FEES);

        verify(ctx).refund(PAYER_ID, FEES);
    }

    @Test
    void refundsWithNodeAccountOnUser() {
        given(ctx.category()).willReturn(HandleContext.TransactionCategory.USER);
        given(ctx.payerId()).willReturn(PAYER_ID);
        given(ctx.nodeAccountId()).willReturn(CREATOR_ID);

        subject.refund(ctx, FEES);

        verify(ctx).refund(PAYER_ID, FEES, CREATOR_ID);
    }
}
