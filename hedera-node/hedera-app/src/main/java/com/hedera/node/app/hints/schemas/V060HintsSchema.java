// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static com.hedera.hapi.node.state.hints.CRSStage.COMPLETED;
import static com.hedera.hapi.node.state.hints.CRSStage.GATHERING_CONTRIBUTIONS;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINT_CONSTRUCTION_KEY;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINT_CONSTRUCTION_KEY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.impl.HintsContext;
import com.hedera.node.config.data.TssConfig;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class V060HintsSchema extends Schema {
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().minor(60).build();
    public static final String CRS_STATE_KEY = "CRS_STATE";
    public static final String CRS_PUBLICATIONS_KEY = "CRS_PUBLICATIONS";
    private static final Logger log = LogManager.getLogger(V060HintsSchema.class);

    private static final long MAX_CRS_PUBLICATIONS = 1L << 10;

    private final HintsContext signingContext;
    private final HintsLibrary library;

    public V060HintsSchema(@NonNull final HintsContext signingContext, @NonNull final HintsLibrary library) {
        super(VERSION);
        this.signingContext = requireNonNull(signingContext);
        this.library = requireNonNull(library);
    }

    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.singleton(CRS_STATE_KEY, CRSState.PROTOBUF),
                StateDefinition.onDisk(
                        CRS_PUBLICATIONS_KEY,
                        NodeId.PROTOBUF,
                        CrsPublicationTransactionBody.PROTOBUF,
                        MAX_CRS_PUBLICATIONS));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        // We have to ensure non-null singletons in restart() due to the sequencing of
        // hinTS feature flag enablements, so doing here for CRS is redundant
    }

    @Override
    public void restart(@NonNull final MigrationContext ctx) {
        final var states = ctx.newStates();
        // Ensure non-null singletons no matter if hinTS is enabled
        final var activeConstructionState = states.<HintsConstruction>getSingleton(ACTIVE_HINT_CONSTRUCTION_KEY);
        if (activeConstructionState.get() == null) {
            activeConstructionState.put(HintsConstruction.DEFAULT);
        }
        final var nextConstructionState = states.<HintsConstruction>getSingleton(NEXT_HINT_CONSTRUCTION_KEY);
        if (nextConstructionState.get() == null) {
            nextConstructionState.put(HintsConstruction.DEFAULT);
        }
        final var crsStateSingleton = states.<CRSState>getSingleton(CRS_STATE_KEY);
        if (crsStateSingleton.get() == null) {
            crsStateSingleton.put(CRSState.DEFAULT);
        }

        // And now if hinTS is enabled, ensure everything is ready for that
        final var tssConfig = ctx.appConfig().getConfigData(TssConfig.class);
        if (tssConfig.hintsEnabled()) {
            final var crsState = requireNonNull(crsStateSingleton.get());
            if (crsState.equals(CRSState.DEFAULT)) {
                log.info("Initializing CRS for {} parties", tssConfig.initialCrsParties());
                final var initialCrs = library.newCrs(tssConfig.initialCrsParties());
                crsStateSingleton.put(CRSState.newBuilder()
                        .stage(GATHERING_CONTRIBUTIONS)
                        .nextContributingNodeId(0L)
                        .crs(initialCrs)
                        .build());
            } else if (crsState.stage() == COMPLETED) {
                signingContext.setCrs(crsState.crs());
            }
            final var activeConstruction = states.<HintsConstruction>getSingleton(ACTIVE_HINT_CONSTRUCTION_KEY)
                    .get();
            if (requireNonNull(activeConstruction).hasHintsScheme()) {
                signingContext.setConstruction(activeConstruction);
            }
        }
    }
}
