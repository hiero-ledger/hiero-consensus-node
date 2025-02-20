// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service.schemas;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterState;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Roster Schema
 */
public class V0540RosterBaseSchema extends Schema {
    public static final String ROSTER_KEY = "ROSTERS";
    public static final String ROSTER_STATES_KEY = "ROSTER_STATE";

    private static final Logger log = LogManager.getLogger(V0540RosterBaseSchema.class);
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
        super(VERSION);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.singleton(ROSTER_STATES_KEY, RosterState.PROTOBUF),
                StateDefinition.onDisk(ROSTER_KEY, ProtoBytes.PROTOBUF, Roster.PROTOBUF, MAX_ROSTERS));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        final var rosterState = ctx.newStates().getSingleton(ROSTER_STATES_KEY);
        // On genesis, create a default roster state from the genesis network info
        if (rosterState.get() == null) {
            log.info("Creating default roster state");
            rosterState.put(RosterState.DEFAULT);
        }
    }
}
