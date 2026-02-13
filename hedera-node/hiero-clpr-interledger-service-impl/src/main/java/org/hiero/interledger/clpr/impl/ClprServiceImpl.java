// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import com.hedera.node.app.spi.RpcService;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.interledger.clpr.ClprService;
import org.hiero.interledger.clpr.impl.schemas.V0700ClprSchema;

/**
 * Standard implementation of the {@link ClprService} {@link RpcService}.
 */
public final class ClprServiceImpl implements ClprService {

    /**
     * Message running hash
     */
    public static final int RUNNING_HASH_SIZE = 48;

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0700ClprSchema());
    }
}
