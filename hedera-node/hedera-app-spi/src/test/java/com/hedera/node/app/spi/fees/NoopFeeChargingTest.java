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
