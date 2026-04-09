// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl;

import com.hedera.node.app.service.util.UtilService;
import com.hedera.node.app.service.util.impl.cache.TransactionParser;
import com.hedera.node.app.service.util.impl.calculator.AtomicBatchFeeCalculator;
import com.hedera.node.app.service.util.impl.calculator.UtilPrngFeeCalculator;
import com.hedera.node.app.service.util.impl.handlers.UtilHandlers;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/** Standard implementation of the {@link UtilService} {@link RpcService}. */
public final class UtilServiceImpl implements UtilService {
    private final UtilServiceComponent component;

    public UtilServiceImpl(@NonNull final AppContext appContext, @NonNull final TransactionParser parser) {
        this.component = DaggerUtilServiceComponent.factory().create(appContext, parser);
    }

    public UtilHandlers handlers() {
        return component.handlers();
    }

    @Override
    public Set<ServiceFeeCalculator> serviceFeeCalculators() {
        return Set.of(new UtilPrngFeeCalculator(), new AtomicBatchFeeCalculator());
    }
}
