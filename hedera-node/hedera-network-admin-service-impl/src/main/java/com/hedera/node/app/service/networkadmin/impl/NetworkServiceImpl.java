// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl;

import com.hedera.node.app.service.networkadmin.NetworkService;
import com.hedera.node.app.service.networkadmin.impl.calculator.GetByKeyFeeCalculator;
import com.hedera.node.app.service.networkadmin.impl.calculator.GetVersionInfoFeeCalculator;
import com.hedera.node.app.service.networkadmin.impl.calculator.TransactionGetReceiptFeeCalculator;
import com.hedera.node.app.service.networkadmin.impl.calculator.TransactionGetRecordFeeCalculator;
import com.hedera.node.app.service.networkadmin.impl.calculator.UncheckedSubmitFeeCalculator;
import com.hedera.node.app.service.networkadmin.impl.schemas.V0490NetworkSchema;
import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.spi.fees.QueryFeeCalculator;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Standard implementation of the {@link NetworkService} {@link RpcService}.
 */
public final class NetworkServiceImpl implements NetworkService {
    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0490NetworkSchema());
    }

    @Override
    public Set<ServiceFeeCalculator> serviceFeeCalculators() {
        return Set.of(new UncheckedSubmitFeeCalculator());
    }
    @Override
    public Set<QueryFeeCalculator> queryFeeCalculators() {
        return Set.of(
                new GetVersionInfoFeeCalculator(),
                new TransactionGetRecordFeeCalculator(),
                new TransactionGetReceiptFeeCalculator(),
                new GetByKeyFeeCalculator());
    }
}
