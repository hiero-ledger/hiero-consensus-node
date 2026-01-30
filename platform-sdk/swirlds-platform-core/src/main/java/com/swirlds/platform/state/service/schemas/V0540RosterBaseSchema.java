// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterState;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.consensus.roster.RosterStateId;

/**
 * Roster Schema
 */
public class V0540RosterBaseSchema extends Schema<SemanticVersion> {
    /**
     * this can't be increased later so we pick some number large enough, 2^16.
     */
    private static final long MAX_ROSTERS = 65_536L;

    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(54).patch(0).build();

    /**
     * Create a new instance
     */
    public V0540RosterBaseSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.singleton(
                        RosterStateId.ROSTER_STATE_STATE_ID, RosterStateId.ROSTER_STATE_KEY, RosterState.PROTOBUF),
                StateDefinition.onDisk(
                        RosterStateId.ROSTERS_STATE_ID,
                        RosterStateId.ROSTERS_KEY,
                        ProtoBytes.PROTOBUF,
                        Roster.PROTOBUF,
                        MAX_ROSTERS));
    }
}
