// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.MAX_QUEUE_DEPTH_HIGH_WATER_KEY;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.MAX_QUEUE_DEPTH_HIGH_WATER_STATE_ID;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/** Adds local queue accounting without changing the configuration proved to peers. */
public class V0800ClprSchema extends Schema<SemanticVersion> {
    public V0800ClprSchema() {
        super(SemanticVersion.newBuilder().minor(80).build(), SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        // Handle-time genesis/post-upgrade setup initializes this from the active ledger config.
        return Set.of(StateDefinition.singleton(
                MAX_QUEUE_DEPTH_HIGH_WATER_STATE_ID, MAX_QUEUE_DEPTH_HIGH_WATER_KEY, ProtoLong.PROTOBUF));
    }
}
