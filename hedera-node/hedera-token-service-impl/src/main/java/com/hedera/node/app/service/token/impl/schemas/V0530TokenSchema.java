// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.schemas;

import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class V0530TokenSchema extends Schema {
    private static final Logger logger = LogManager.getLogger(V0530TokenSchema.class);
    private static final long MAX_PENDING_AIRDROPS = 1_000_000_000L;
    public static final String AIRDROPS_KEY = "PENDING_AIRDROPS";

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(53).patch(0).build();

    public V0530TokenSchema() {
        super(VERSION);
    }

    @SuppressWarnings("rawtypes")
    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.onDisk(
                AIRDROPS_KEY, PendingAirdropId.PROTOBUF, AccountPendingAirdrop.PROTOBUF, MAX_PENDING_AIRDROPS));
    }
}
