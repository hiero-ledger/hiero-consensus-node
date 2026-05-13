// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.roster.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.service.roster.RosterTransplantSchema;
import com.hedera.node.app.spi.migrate.HederaMigrationContext;
import com.swirlds.platform.state.service.schemas.V0540RosterBaseSchema;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.hiero.consensus.roster.WritableRosterStore;

/**
 * Initial {@link com.hedera.node.app.service.roster.RosterService} schema that registers two states,
 * <ol>
 *     <li>A mapping from roster hashes to rosters (which may be either candidate or active).</li>
 *     <li>A singleton that contains the history of active rosters along with the round numbers where
 *     they were adopted; along with the hash of a candidate roster if there is one.</li>
 * </ol>
 */
public class V0540RosterSchema extends Schema<SemanticVersion> implements RosterTransplantSchema {
    public static final String ROSTER_KEY = "ROSTERS";
    public static final String ROSTER_STATES_KEY = "ROSTER_STATE";

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(54).patch(0).build();

    /**
     * A callback to invoke with an outgoing roster being replaced by a new roster hash.
     */
    private final BiConsumer<Roster, Roster> onAdopt;
    /**
     * The factory to use to create the writable roster store.
     */
    private final Function<WritableStates, WritableRosterStore> rosterStoreFactory;

    public V0540RosterSchema(
            @NonNull final BiConsumer<Roster, Roster> onAdopt,
            @NonNull final Function<WritableStates, WritableRosterStore> rosterStoreFactory) {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
        this.onAdopt = requireNonNull(onAdopt);
        this.rosterStoreFactory = requireNonNull(rosterStoreFactory);
    }

    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return new V0540RosterBaseSchema().statesToCreate();
    }

    @Override
    public void restart(@NonNull final MigrationContext<SemanticVersion> ctx) {
        requireNonNull(ctx);
        if (!ctx.isGenesis()) {
            RosterTransplantSchema.super.restart((HederaMigrationContext) ctx, onAdopt, rosterStoreFactory);
        }
    }
}
