// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.roster.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.service.roster.impl.schemas.V0540RosterSchema;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.consensus.roster.WritableRosterStore;

/**
 * A {@link com.hedera.hapi.node.state.roster.Roster} implementation of the {@link Service} interface.
 * Registers the roster schemas with the {@link SchemaRegistry}.
 * Not exposed outside `hedera-app`.
 */
public class RosterServiceImpl implements Service {
    public static final int MIGRATION_ORDER = PlatformStateService.PLATFORM_MIGRATION_ORDER - 1;

    public static final String NAME = "RosterService";

    /**
     * The test to use to determine if a candidate roster may be
     * adopted at an upgrade boundary.
     */
    private final Predicate<Roster> canAdopt;
    /**
     * A callback to invoke with an outgoing roster being replaced by a new roster hash.
     */
    private final BiConsumer<Roster, Roster> onAdopt;
    /**
     * Required until the upgrade that adopts the roster lifecycle; at that upgrade boundary,
     * we must initialize the active roster from the platform state's legacy address books.
     */
    @Deprecated
    private final Supplier<State> stateSupplier;

    private final Supplier<StartupNetworks> startupNetworks;

    public RosterServiceImpl(
            @NonNull final Predicate<Roster> canAdopt,
            @NonNull final BiConsumer<Roster, Roster> onAdopt,
            @NonNull final Supplier<State> stateSupplier,
            @NonNull final Supplier<StartupNetworks> startupNetworks) {
        this.onAdopt = requireNonNull(onAdopt);
        this.canAdopt = requireNonNull(canAdopt);
        this.stateSupplier = requireNonNull(stateSupplier);
        this.startupNetworks = requireNonNull(startupNetworks);
    }

    @NonNull
    @Override
    public String getServiceName() {
        return NAME;
    }

    @Override
    public int migrationOrder() {
        return MIGRATION_ORDER;
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
        registry.register(new V0540RosterSchema(onAdopt, canAdopt, WritableRosterStore::new));
    }

    @Override
    public boolean doGenesisSetup(
            @NonNull final WritableStates writableStates, @NonNull final Configuration configuration) {
        requireNonNull(writableStates);
        requireNonNull(configuration);
        final var rosterStore = new WritableRosterStore(writableStates);
        final var genesisNetwork = startupNetworks.get().genesisNetworkOrThrow(configuration);
        rosterStore.putActiveRoster(RosterUtils.rosterFrom(genesisNetwork), 0L);
        return true;
    }
}
