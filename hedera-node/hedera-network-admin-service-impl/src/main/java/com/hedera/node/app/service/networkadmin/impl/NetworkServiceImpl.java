// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl;

import com.hedera.node.app.service.networkadmin.NetworkService;
import com.hedera.node.app.service.networkadmin.impl.schemas.V0490NetworkSchema;
import com.hedera.node.app.spi.RpcService;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Standard implementation of the {@link NetworkService} {@link RpcService}.
 */
public final class NetworkServiceImpl implements NetworkService {
    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0490NetworkSchema());
    }
}
