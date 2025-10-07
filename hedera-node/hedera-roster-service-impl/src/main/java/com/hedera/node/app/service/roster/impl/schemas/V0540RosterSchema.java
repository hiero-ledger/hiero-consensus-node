// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.roster.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.service.roster.RosterTransplantSchema;
import com.hedera.node.app.spi.migrate.HederaMigrationContext;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.VersionConfig;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.service.schemas.V0540RosterBaseSchema;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.roster.RosterRetriever;
import org.hiero.consensus.roster.RosterUtils;
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
    private static final Logger log = LogManager.getLogger(V0540RosterSchema.class);

    public static final String ROSTER_KEY = "ROSTERS";
    public static final String ROSTER_STATES_KEY = "ROSTER_STATE";

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(54).patch(0).build();

    /**
     * The delegate schema that defines the base roster schema.
     */
    private final V0540RosterBaseSchema baseSchema = new V0540RosterBaseSchema();
    /**
     * A callback to invoke with an outgoing roster being replaced by a new roster hash.
     */
    private final BiConsumer<Roster, Roster> onAdopt;
    /**
     * The test to use to determine if a candidate roster may be adopted at an upgrade boundary.
     */
    private final Predicate<Roster> canAdopt;
    /**
     * The factory to use to create the writable roster store.
     */
    private final Function<WritableStates, WritableRosterStore> rosterStoreFactory;
    /**
     * Can be removed after no production states are left without a roster.
     */
    @Deprecated
    private final Supplier<State> stateSupplier;

    private final PlatformStateFacade platformStateFacade;

    public V0540RosterSchema(
            @NonNull final BiConsumer<Roster, Roster> onAdopt,
            @NonNull final Predicate<Roster> canAdopt,
            @NonNull final Function<WritableStates, WritableRosterStore> rosterStoreFactory,
            @NonNull final Supplier<State> stateSupplier,
            @NonNull final PlatformStateFacade platformStateFacade) {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
        this.onAdopt = requireNonNull(onAdopt);
        this.canAdopt = requireNonNull(canAdopt);
        this.rosterStoreFactory = requireNonNull(rosterStoreFactory);
        this.stateSupplier = requireNonNull(stateSupplier);
        this.platformStateFacade = platformStateFacade;
    }

    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return baseSchema.statesToCreate();
    }

    @Override
    public void restart(@NonNull final MigrationContext<SemanticVersion> ctx) {
        requireNonNull(ctx);
        final HederaMigrationContext hederaCtx = (HederaMigrationContext) ctx;
        if (!RosterTransplantSchema.super.restart(hederaCtx, onAdopt, rosterStoreFactory)) {
            final StartupNetworks startupNetworks = hederaCtx.startupNetworks();
            final var rosterStore = rosterStoreFactory.apply(ctx.newStates());
            final var activeRoundNumber = ctx.roundNumber() + 1;
            if (ctx.isGenesis()) {
                rosterStore.putActiveRoster(
                        RosterUtils.rosterFrom(startupNetworks.genesisNetworkOrThrow(ctx.platformConfig())), 0L);
            } else if (rosterStore.getActiveRoster() == null) {
                // (FUTURE) Once there are no production states without a roster, we can remove this branch
                final State state = stateSupplier.get();
                final long round = platformStateFacade.roundOf(state);
                final var previousRoster = requireNonNull(RosterRetriever.retrieveActive(state, round));
                rosterStore.putActiveRoster(previousRoster, 0);
                final var currentRoster =
                        RosterUtils.rosterFrom(startupNetworks.migrationNetworkOrThrow(ctx.platformConfig()));
                rosterStore.putActiveRoster(currentRoster, activeRoundNumber);
            } else if (ctx.isUpgrade(ctx.appConfig()
                    .getConfigData(VersionConfig.class)
                    .servicesVersion()
                    .copyBuilder()
                    .build(""
                            + ctx.appConfig().getConfigData(HederaConfig.class).configVersion())
                    .build())) {
                final var candidateRoster = rosterStore.getCandidateRoster();
                if (candidateRoster == null) {
                    log.info("No candidate roster to adopt in round {}", activeRoundNumber);
                } else if (canAdopt.test(candidateRoster)) {
                    log.info("Adopting candidate roster in round {}", activeRoundNumber);
                    onAdopt.accept(requireNonNull(rosterStore.getActiveRoster()), candidateRoster);
                    rosterStore.adoptCandidateRoster(activeRoundNumber);
                } else {
                    log.info("Rejecting candidate roster in round {}", activeRoundNumber);
                }
            }
        }
    }
}
