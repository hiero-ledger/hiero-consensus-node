// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.node.app.hapi.utils.fee.FeeConstants.FEE_DIVISOR_FACTOR;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.FeeComponents;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.node.app.spi.fees.ResourcePriceCalculator;
import com.hedera.node.app.spi.workflows.FunctionalityResourcePrices;
import com.hedera.node.app.store.ReadableStoreFactoryImpl;
import com.hedera.node.app.workflows.TransactionInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import javax.inject.Inject;

/**
 * The default implementation of {@link ResourcePriceCalculator}.
 */
public class ResourcePriceCalculatorImpl implements ResourcePriceCalculator {

    private Instant consensusNow;
    private TransactionInfo txnInfo;
    private FeeManager feeManager;
    private ReadableStoreFactoryImpl readableStoreFactory;

    @Inject
    public ResourcePriceCalculatorImpl(
            @NonNull final Instant consensusNow,
            @NonNull final TransactionInfo txnInfo,
            @NonNull final FeeManager feeManager,
            @NonNull final ReadableStoreFactoryImpl readableStoreFactory) {
        reset(consensusNow, txnInfo, feeManager, readableStoreFactory);
    }

    /**
     * Rebinds this calculator to the next parent dispatch. Handle is single-threaded.
     */
    public void reset(
            @NonNull final Instant consensusNow,
            @NonNull final TransactionInfo txnInfo,
            @NonNull final FeeManager feeManager,
            @NonNull final ReadableStoreFactoryImpl readableStoreFactory) {
        this.consensusNow = requireNonNull(consensusNow);
        this.txnInfo = requireNonNull(txnInfo);
        this.feeManager = requireNonNull(feeManager);
        this.readableStoreFactory = requireNonNull(readableStoreFactory);
    }

    @NonNull
    @Override
    public FunctionalityResourcePrices resourcePricesFor(
            @NonNull HederaFunctionality functionality, @NonNull SubType subType) {
        // Base prices synthesized from the simple fee schedule; the legacy fee-schedule units are
        // thousandths of a tinycent, and all non-gas resource prices (e.g. RBH) are zero, matching
        // the values the retired file-111 schedules carried for every EVM transaction type
        final var basePrices = FeeData.newBuilder()
                .servicedata(FeeComponents.newBuilder()
                        .gas(feeManager.getGasPriceInTinyCents(consensusNow) * FEE_DIVISOR_FACTOR)
                        .build())
                .build();
        return new FunctionalityResourcePrices(
                basePrices, feeManager.congestionMultiplierFor(txnInfo.txBody(), functionality, readableStoreFactory));
    }
}
