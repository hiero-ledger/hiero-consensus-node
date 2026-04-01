// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.impl.HintsContext;
import com.hedera.node.config.data.TssConfig;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.roster.ReadableRosterStoreImpl;

/**
 * Initializes hinTS singleton states on non-genesis restart if they are absent.
 */
public class V073HintsSchema extends Schema<SemanticVersion> {
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(73).patch(0).build();

    private final HintsLibrary library;
    private final HintsContext signingContext;

    public V073HintsSchema(@NonNull final HintsLibrary library, @NonNull final HintsContext signingContext) {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
        this.library = requireNonNull(library);
        this.signingContext = requireNonNull(signingContext);
    }

    @Override
    public void restart(@NonNull final MigrationContext ctx) {
        if (ctx.isGenesis() || !ctx.appConfig().getConfigData(TssConfig.class).hintsEnabled()) {
            return;
        }
        final var writableStates = ctx.newStates();
        final var activeConstructionState =
                writableStates.<HintsConstruction>getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID);
        var activeConstruction = activeConstructionState.get();
        if (activeConstruction == null) {
            activeConstructionState.put(HintsConstruction.DEFAULT);
            activeConstruction = activeConstructionState.get();
            if (activeConstruction != null && activeConstruction.hasHintsScheme()) {
                signingContext.setConstruction(activeConstruction);
            }
        }
        if (writableStates
                        .<HintsConstruction>getSingleton(NEXT_HINTS_CONSTRUCTION_STATE_ID)
                        .get()
                == null) {
            writableStates
                    .<HintsConstruction>getSingleton(NEXT_HINTS_CONSTRUCTION_STATE_ID)
                    .put(HintsConstruction.DEFAULT);
        }
        if (writableStates.<CRSState>getSingleton(CRS_STATE_STATE_ID).get() == null) {
            final var rosterStore = new ReadableRosterStoreImpl(ctx.newStates());
            final var activeRoster = rosterStore.getActiveRoster();
            final var candidateRoster = rosterStore.getCandidateRoster();
            final var roster = activeRoster != null ? activeRoster : candidateRoster;
            requireNonNull(roster, "Cannot initialize CRS state without an active or candidate roster");
            final var crs = library.newCrs((short) HintsService.partySizeForRosterNodeCount(
                    roster.rosterEntries().size()));
            writableStates
                    .<CRSState>getSingleton(CRS_STATE_STATE_ID)
                    .put(CRSState.newBuilder()
                            .stage(com.hedera.hapi.node.state.hints.CRSStage.GATHERING_CONTRIBUTIONS)
                            .nextContributingNodeId(0L)
                            .crs(crs)
                            .build());
        }
    }
}
