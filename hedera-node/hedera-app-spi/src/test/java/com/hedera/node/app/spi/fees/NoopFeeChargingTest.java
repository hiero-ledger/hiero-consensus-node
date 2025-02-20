// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import static com.hedera.node.app.spi.fees.NoopFeeCharging.NOOP_FEE_CHARGING;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.workflows.HandleContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NoopFeeChargingTest {
    @Mock
    private FeeCharging.Context ctx;

    @Mock
    private FeeCharging.Validation validation;

    @Test
    void validationAlwaysPasses() {
        final var validation = NOOP_FEE_CHARGING.validate(
                Account.DEFAULT,
                AccountID.DEFAULT,
                Fees.FREE,
                TransactionBody.DEFAULT,
                false,
                HederaFunctionality.CRYPTO_TRANSFER,
                HandleContext.TransactionCategory.USER);
        assertTrue(validation.creatorDidDueDiligence());
        assertNull(validation.maybeErrorStatus());
    }

    @Test
    void chargingIsNoop() {
        NOOP_FEE_CHARGING.charge(ctx, validation, Fees.FREE);
        verifyNoInteractions(ctx, validation);
    }
}
