// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import com.hedera.node.app.spi.RpcService;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.interledger.clpr.ClprService;
import org.hiero.interledger.clpr.impl.schemas.V0650ClprSchema;

/**
 * Standard implementation of the {@link ClprService} {@link RpcService}.
 */
public final class ClprServiceImpl implements ClprService {
    private LedgerConfigurationDispatcher dispatcher;

    @Override
    public void setTransactionDispatcher(@NonNull final LedgerConfigurationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void dispatchLedgerConfigurationUpdate(@NonNull final State state, @NonNull final Instant consensusTime) {
        if (dispatcher != null) {
            dispatcher.dispatch(state, consensusTime);
        }
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0650ClprSchema());
    }
}
