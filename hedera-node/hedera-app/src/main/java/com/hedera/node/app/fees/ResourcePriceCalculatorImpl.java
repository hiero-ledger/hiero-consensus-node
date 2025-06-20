// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.node.app.spi.fees.ResourcePriceCalculator;
import com.hedera.node.app.spi.workflows.FunctionalityResourcePrices;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.TransactionInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import javax.inject.Inject;

/**
 * The default implementation of {@link ResourcePriceCalculator}.
 */
public class ResourcePriceCalculatorImpl implements ResourcePriceCalculator {

    private final Instant consensusNow;
    private final TransactionInfo txnInfo;
    private final FeeManager feeManager;
    private final ReadableStoreFactory readableStoreFactory;

    @Inject
    public ResourcePriceCalculatorImpl(
            @NonNull final Instant consensusNow,
            @NonNull final TransactionInfo txnInfo,
            @NonNull final FeeManager feeManager,
            @NonNull final ReadableStoreFactory readableStoreFactory) {
        this.consensusNow = requireNonNull(consensusNow);
        this.txnInfo = requireNonNull(txnInfo);
        this.feeManager = requireNonNull(feeManager);
        this.readableStoreFactory = requireNonNull(readableStoreFactory);
    }

    @NonNull
    @Override
    public FunctionalityResourcePrices resourcePricesFor(
            @NonNull HederaFunctionality functionality, @NonNull SubType subType) {
        return new FunctionalityResourcePrices(
                requireNonNull(feeManager.getFeeData(functionality, consensusNow, subType)),
                feeManager.congestionMultiplierFor(txnInfo.txBody(), functionality, readableStoreFactory));
    }
}
