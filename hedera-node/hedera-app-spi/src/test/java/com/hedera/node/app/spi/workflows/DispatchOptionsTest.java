// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import static com.hedera.node.app.spi.fees.NoopFeeCharging.DISPATCH_ONLY_NOOP_FEE_CHARGING;
import static com.hedera.node.app.spi.fees.NoopFeeCharging.UNIVERSAL_NOOP_FEE_CHARGING;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CUSTOM_FEE_CHARGING;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DispatchOptionsTest {
    @Test
    void stepDispatchCanUseCustomFeeCharging() {
        final var options = DispatchOptions.stepDispatch(
                AccountID.DEFAULT,
                TransactionBody.DEFAULT,
                StreamBuilder.class,
                NOOP_SIGNED_TX_CUSTOMIZER,
                DISPATCH_ONLY_NOOP_FEE_CHARGING);

        assertSame(DISPATCH_ONLY_NOOP_FEE_CHARGING, options.customFeeCharging());
    }

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
                UNIVERSAL_NOOP_FEE_CHARGING,
                DispatchOptions.PropagateFeeChargingStrategy.YES);

        final var maybeFeeCharging = options.dispatchMetadata().getMetadata(CUSTOM_FEE_CHARGING, FeeCharging.class);
        assertTrue(maybeFeeCharging.isPresent());
        assertSame(UNIVERSAL_NOOP_FEE_CHARGING, maybeFeeCharging.get());
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
                UNIVERSAL_NOOP_FEE_CHARGING,
                DispatchOptions.PropagateFeeChargingStrategy.NO);

        final var maybeFeeCharging = options.dispatchMetadata().getMetadata(CUSTOM_FEE_CHARGING, FeeCharging.class);
        assertTrue(maybeFeeCharging.isEmpty());
    }
}
