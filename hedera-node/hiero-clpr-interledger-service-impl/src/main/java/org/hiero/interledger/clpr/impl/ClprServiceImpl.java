// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.RpcService;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.interledger.clpr.ClprService;

/**
 * Standard implementation of the {@link ClprService} {@link RpcService}.
 */
public final class ClprServiceImpl implements ClprService {

    public ClprServiceImpl(@NonNull final AppContext appContext) {
        requireNonNull(appContext);
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {}
}
