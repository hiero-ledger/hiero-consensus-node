// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl;

import com.hedera.node.app.service.consensus.ConsensusService;
import com.hedera.node.app.service.consensus.impl.calculator.ConsensusCreateTopicFeeCalculator;
import com.hedera.node.app.service.consensus.impl.calculator.ConsensusDeleteTopicFeeCalculator;
import com.hedera.node.app.service.consensus.impl.calculator.ConsensusGetTopicInfoFeeCalculator;
import com.hedera.node.app.service.consensus.impl.calculator.ConsensusSubmitMessageFeeCalculator;
import com.hedera.node.app.service.consensus.impl.calculator.ConsensusUpdateTopicFeeCalculator;
import com.hedera.node.app.service.consensus.impl.schemas.V0490ConsensusSchema;
import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.spi.fees.QueryFeeCalculator;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Standard implementation of the {@link ConsensusService} {@link RpcService}.
 */
public final class ConsensusServiceImpl implements ConsensusService {

    /**
     * Topic running hash
     */
    public static final int RUNNING_HASH_BYTE_ARRAY_SIZE = 48;

    @Override
    public void registerSchemas(@NonNull SchemaRegistry registry) {
        registry.register(new V0490ConsensusSchema());
    }

    @Override
    public Set<ServiceFeeCalculator> serviceFeeCalculators() {
        return Set.of(
                new ConsensusCreateTopicFeeCalculator(),
                new ConsensusDeleteTopicFeeCalculator(),
                new ConsensusUpdateTopicFeeCalculator(),
                new ConsensusSubmitMessageFeeCalculator());
    }

    @Override
    public Set<QueryFeeCalculator> queryFeeCalculators() {
        return Set.of(new ConsensusGetTopicInfoFeeCalculator());
    }
}
