// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.service.token.TokenService;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

public class V0530TokenSchema extends Schema<SemanticVersion> {

    private static final long MAX_PENDING_AIRDROPS = 1_000_000L;

    public static final int AIRDROPS_STATE_ID = StateKey.KeyOneOfType.TOKENSERVICE_I_PENDING_AIRDROPS.protoOrdinal();
    public static final String AIRDROPS_KEY = "PENDING_AIRDROPS";
    public static final String AIRDROPS_STATE_LABEL = computeLabel(TokenService.NAME, AIRDROPS_KEY);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(53).patch(0).build();

    public V0530TokenSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @SuppressWarnings("rawtypes")
    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.onDisk(
                AIRDROPS_STATE_ID,
                AIRDROPS_KEY,
                PendingAirdropId.PROTOBUF,
                AccountPendingAirdrop.PROTOBUF,
                MAX_PENDING_AIRDROPS));
    }
}
