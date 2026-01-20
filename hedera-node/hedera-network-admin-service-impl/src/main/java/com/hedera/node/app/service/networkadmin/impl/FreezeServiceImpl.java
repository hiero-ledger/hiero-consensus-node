// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl;

import static com.hedera.node.app.service.networkadmin.impl.schemas.V0490FreezeSchema.FREEZE_TIME_STATE_ID;
import static com.hedera.node.app.service.networkadmin.impl.schemas.V0490FreezeSchema.UPGRADE_FILE_HASH_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.service.networkadmin.FreezeService;
import com.hedera.node.app.service.networkadmin.impl.schemas.V0490FreezeSchema;
import com.hedera.node.app.spi.RpcService;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Standard implementation of the {@link FreezeService} {@link RpcService}.
 * */
public final class FreezeServiceImpl implements FreezeService {
    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0490FreezeSchema());
    }

    @Override
    public boolean doGenesisSetup(
            @NonNull final WritableStates writableStates, @NonNull final Configuration configuration) {
        requireNonNull(writableStates);
        requireNonNull(configuration);
        writableStates.<ProtoBytes>getSingleton(UPGRADE_FILE_HASH_STATE_ID).put(ProtoBytes.DEFAULT);
        writableStates.<Timestamp>getSingleton(FREEZE_TIME_STATE_ID).put(Timestamp.DEFAULT);
        return true;
    }
}
