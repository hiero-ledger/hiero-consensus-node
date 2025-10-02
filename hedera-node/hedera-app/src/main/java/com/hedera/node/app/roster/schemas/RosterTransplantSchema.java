// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.roster.schemas;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.roster.RosterService;
import com.hedera.node.app.services.HederaMigrationContext;
import com.hedera.node.app.services.StartupNetworks;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.consensus.roster.WritableRosterStore;

/**
 * The {@link Schema#restart(MigrationContext)} implementation whereby the {@link RosterService} ensures that any
 * roster overrides in the startup assets are copied into the state.
 * <p>
 * <b>Important:</b> The latest {@link RosterService} schema should always implement this interface.
 */
public interface RosterTransplantSchema {
    Logger log = LogManager.getLogger(RosterTransplantSchema.class);

    /**
     * Restart the {@link RosterService} by copying any roster overrides from the startup assets into the state.
     *
     * @param ctx the migration context
     * @param onAdopt a callback to invoke with an outgoing roster being replaced by a new roster hash
     * @param rosterStoreFactory the factory to use to create the writable roster store
     */
    default boolean restart(
            @NonNull final HederaMigrationContext ctx,
            @NonNull final BiConsumer<Roster, Roster> onAdopt,
            @NonNull final Function<WritableStates, WritableRosterStore> rosterStoreFactory) {
        requireNonNull(ctx);
        final long roundNumber = ctx.roundNumber();
        final StartupNetworks startupNetworks = ctx.startupNetworks();
        final var overrideNetwork = startupNetworks.overrideNetworkFor(roundNumber, ctx.platformConfig());
        overrideNetwork.ifPresent(network -> {
            final long activeRoundNumber = roundNumber + 1;
            log.info("Adopting roster from override network in round {}", activeRoundNumber);
            final var rosterStore = rosterStoreFactory.apply(ctx.newStates());
            final var outgoingRoster = requireNonNull(rosterStore.getActiveRoster());
            final var overrideRoster = RosterUtils.rosterFrom(network);
            final var networkAdminConfig = ctx.appConfig().getConfigData(NetworkAdminConfig.class);
            final var roster = networkAdminConfig.preserveStateWeightsDuringOverride()
                    ? withExtantNodeWeights(overrideRoster, rosterStore.getActiveRoster())
                    : overrideRoster;
            rosterStore.putActiveRoster(roster, activeRoundNumber);
            rosterStore.updateTransplantInProgress(true);
            onAdopt.accept(outgoingRoster, roster);
            startupNetworks.setOverrideRound(roundNumber);
        });
        return overrideNetwork.isPresent();
    }

    /**
     * If there are weights to copy to the first roster from the (possibly null) second roster, then does so and
     * returns a new roster with the result.
     *
     * @param to the roster to copy weights to
     * @param from the roster to copy weights from
     * @return a roster with any weights copied by matching node id
     */
    private static Roster withExtantNodeWeights(@NonNull final Roster to, @Nullable final Roster from) {
        if (from == null) {
            return to;
        }
        final Map<Long, Long> fromNodeWeights =
                from.rosterEntries().stream().collect(toMap(RosterEntry::nodeId, RosterEntry::weight));
        return new Roster(to.rosterEntries().stream()
                .map(entry -> entry.copyBuilder()
                        .weight(fromNodeWeights.getOrDefault(entry.nodeId(), entry.weight()))
                        .build())
                .collect(toList()));
    }
}
