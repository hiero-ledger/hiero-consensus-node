// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl;

import com.hedera.node.app.service.consensus.ConsensusService;
import com.hedera.node.app.service.consensus.impl.schemas.V0490ConsensusSchema;
import com.hedera.node.app.spi.RpcService;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;

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
}
