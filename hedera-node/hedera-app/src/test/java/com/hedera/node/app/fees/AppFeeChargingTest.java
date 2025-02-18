/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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

        subject.charge(ctx, result, FEES);

        verify(ctx).charge(PAYER_ID, FEES, CREATOR_ID);
    }

    @Test
    void waivesServiceFeeIfPayerUnableToAffordSvcComponent() {
        final var result = ValidationResult.newSuccess(CREATOR_ID, PAYER).withoutServiceFee();
        given(ctx.category()).willReturn(HandleContext.TransactionCategory.USER);

        subject.charge(ctx, result, FEES);

        verify(ctx).charge(PAYER_ID, FEES.withoutServiceComponent(), CREATOR_ID);
    }

    @Test
    void waivesServiceFeeOnDuplicate() {
        final var result = ValidationResult.newPayerDuplicateError(CREATOR_ID, PAYER);
        given(ctx.category()).willReturn(HandleContext.TransactionCategory.USER);

        subject.charge(ctx, result, FEES);

        verify(ctx).charge(PAYER_ID, FEES.withoutServiceComponent(), CREATOR_ID);
    }

    @Test
    void defaultsToSkippingNodeAccountDisbursement() {
        final var result = ValidationResult.newSuccess(CREATOR_ID, PAYER);
        given(ctx.category()).willReturn(HandleContext.TransactionCategory.SCHEDULED);

        subject.charge(ctx, result, FEES);

        verify(ctx).charge(PAYER_ID, FEES);
    }
}
