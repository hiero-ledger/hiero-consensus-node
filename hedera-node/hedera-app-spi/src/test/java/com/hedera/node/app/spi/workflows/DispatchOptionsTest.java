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

package com.hedera.node.app.spi.workflows;

import static com.hedera.node.app.spi.fees.NoopFeeCharging.NOOP_FEE_CHARGING;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CUSTOM_FEE_CHARGING;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DispatchOptionsTest {
    @Test
    void propagatesSubDispatchCustomFeeChargingViaExpectedKeyIfRequested() {
        final var options = DispatchOptions.subDispatch(
                AccountID.DEFAULT,
                TransactionBody.DEFAULT,
                ignore -> true,
                Set.of(),
                StreamBuilder.class,
                DispatchOptions.StakingRewards.OFF,
                DispatchOptions.UsePresetTxnId.YES,
                NOOP_FEE_CHARGING,
                DispatchOptions.PropagateFeeChargingStrategy.YES);

        final var maybeFeeCharging = options.dispatchMetadata().getMetadata(CUSTOM_FEE_CHARGING, FeeCharging.class);
        assertTrue(maybeFeeCharging.isPresent());
        assertSame(NOOP_FEE_CHARGING, maybeFeeCharging.get());
    }

    @Test
    void doesNotPropagateSubDispatchCustomFeeChargingViaExpectedKeyIfNotRequested() {
        final var options = DispatchOptions.subDispatch(
                AccountID.DEFAULT,
                TransactionBody.DEFAULT,
                ignore -> true,
                Set.of(),
                StreamBuilder.class,
                DispatchOptions.StakingRewards.OFF,
                DispatchOptions.UsePresetTxnId.YES,
                NOOP_FEE_CHARGING,
                DispatchOptions.PropagateFeeChargingStrategy.NO);

        final var maybeFeeCharging = options.dispatchMetadata().getMetadata(CUSTOM_FEE_CHARGING, FeeCharging.class);
        assertTrue(maybeFeeCharging.isEmpty());
    }
}
