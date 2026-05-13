// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.roster.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.service.roster.impl.schemas.V0540RosterSchema;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.PostUpgradeContext;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.platformstate.PlatformStateService;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.consensus.roster.WritableRosterStore;

/**
 * A {@link com.hedera.hapi.node.state.roster.Roster} implementation of the {@link Service} interface.
 * Registers the roster schemas with the {@link SchemaRegistry}.
 * Not exposed outside `hedera-app`.
 */
public class RosterServiceImpl implements Service {
    private static final Logger log = LogManager.getLogger(RosterServiceImpl.class);

    public static final int MIGRATION_ORDER = PlatformStateService.PLATFORM_MIGRATION_ORDER - 1;

    public static final String NAME = "RosterService";

    @FunctionalInterface
    public interface RosterAdoption {
        void accept(
                @NonNull Roster previousRoster,
                @NonNull Roster adoptedRoster,
                @NonNull PostUpgradeContext postUpgradeContext);
    }

    /**
     * The test to use to determine if a candidate roster may be
     * adopted at an upgrade boundary.
     */
    private final BiPredicate<Roster, PostUpgradeContext> canAdopt;
    /**
     * A callback to invoke with an outgoing roster being replaced by a new roster at an upgrade boundary.
     */
    private final RosterAdoption onAdopt;
    /**
     * A callback to invoke with an outgoing roster being replaced by a new roster during migration restart.
     */
    private final BiConsumer<Roster, Roster> onMigrationAdopt;

    private final Supplier<StartupNetworks> startupNetworks;

    private final Function<WritableStates, WritableRosterStore> rosterStoreFactory;

    public RosterServiceImpl(
            @NonNull final Predicate<Roster> canAdopt,
            @NonNull final BiConsumer<Roster, Roster> onAdopt,
            @NonNull final Supplier<StartupNetworks> startupNetworks) {
        this(
                asPostUpgradePredicate(canAdopt),
                asPostUpgradeAdoption(onAdopt),
                onAdopt,
                startupNetworks,
                WritableRosterStore::new);
    }

    public RosterServiceImpl(
            @NonNull final BiPredicate<Roster, PostUpgradeContext> canAdopt,
            @NonNull final RosterAdoption onAdopt,
            @NonNull final BiConsumer<Roster, Roster> onMigrationAdopt,
            @NonNull final Supplier<StartupNetworks> startupNetworks) {
        this(canAdopt, onAdopt, onMigrationAdopt, startupNetworks, WritableRosterStore::new);
    }

    public RosterServiceImpl(
            @NonNull final BiPredicate<Roster, PostUpgradeContext> canAdopt,
            @NonNull final RosterAdoption onAdopt,
            @NonNull final BiConsumer<Roster, Roster> onMigrationAdopt,
            @NonNull final Supplier<StartupNetworks> startupNetworks,
            @NonNull final Function<WritableStates, WritableRosterStore> rosterStoreFactory) {
        this.onAdopt = requireNonNull(onAdopt);
        this.onMigrationAdopt = requireNonNull(onMigrationAdopt);
        this.canAdopt = requireNonNull(canAdopt);
        this.startupNetworks = requireNonNull(startupNetworks);
        this.rosterStoreFactory = requireNonNull(rosterStoreFactory);
    }

    private static BiPredicate<Roster, PostUpgradeContext> asPostUpgradePredicate(
            @NonNull final Predicate<Roster> canAdopt) {
        requireNonNull(canAdopt);
        return (roster, ignore) -> canAdopt.test(roster);
    }

    private static RosterAdoption asPostUpgradeAdoption(@NonNull final BiConsumer<Roster, Roster> onAdopt) {
        requireNonNull(onAdopt);
        return (previousRoster, adoptedRoster, ignore) -> onAdopt.accept(previousRoster, adoptedRoster);
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
        registry.register(new V0540RosterSchema(onMigrationAdopt, rosterStoreFactory));
    }

    @Override
    public boolean doGenesisSetup(
            @NonNull final WritableStates writableStates, @NonNull final Configuration configuration) {
        requireNonNull(writableStates);
        requireNonNull(configuration);
        final var rosterStore = rosterStoreFactory.apply(writableStates);
        final var genesisNetwork = startupNetworks.get().genesisNetworkOrThrow(configuration);
        rosterStore.putActiveRoster(RosterUtils.rosterFrom(genesisNetwork), 0L);
        return true;
    }

    @Override
    public boolean doPostUpgradeSetup(
            @NonNull final WritableStates writableStates, @NonNull final PostUpgradeContext context) {
        requireNonNull(writableStates);
        requireNonNull(context);
        final var rosterStore = rosterStoreFactory.apply(writableStates);
        final var activeRoundNumber = context.roundNumber();
        final var candidateRoster = rosterStore.getCandidateRoster();
        if (candidateRoster == null) {
            log.info("No candidate roster to adopt in round {}", activeRoundNumber);
            return false;
        }
        if (canAdopt.test(candidateRoster, context)) {
            log.info("Adopting candidate roster in round {}", activeRoundNumber);
            onAdopt.accept(requireNonNull(rosterStore.getActiveRoster()), candidateRoster, context);
            rosterStore.adoptCandidateRoster(activeRoundNumber);
            return true;
        }
        log.info("Rejecting candidate roster in round {}", activeRoundNumber);
        return false;
    }
}
