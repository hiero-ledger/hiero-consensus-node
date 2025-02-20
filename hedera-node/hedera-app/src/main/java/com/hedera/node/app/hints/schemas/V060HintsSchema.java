// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINT_CONSTRUCTION_KEY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.impl.HintsContext;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

public class V060HintsSchema extends Schema {
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().minor(60).build();
    public static final String CRS_STATE_KEY = "CRS_STATE";
    public static final String CRS_PUBLICATIONS_KEY = "CRS_PUBLICATIONS";

    private static final long MAX_CRS_PUBLICATIONS = 1L << 31;

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
        final var states = ctx.newStates();
        final var newCrsState = states.<CRSState>getSingleton(CRS_STATE_KEY);
        if (newCrsState.get() == null || requireNonNull(newCrsState.get()).crs().equals(Bytes.EMPTY)) {
            final var tssConfig = ctx.appConfig().getConfigData(TssConfig.class);
            final var initialCrs = library.newCrs(tssConfig.initialCrsParties());
            states.<CRSState>getSingleton(CRS_STATE_KEY)
                    .put(CRSState.newBuilder()
                            .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                            .nextContributingNodeId(0L)
                            .crs(initialCrs)
                            .build());
        }
    }

    @Override
    public void restart(@NonNull final MigrationContext ctx) {
        final var states = ctx.newStates();
        final var activeConstruction =
                requireNonNull(states.<HintsConstruction>getSingleton(ACTIVE_HINT_CONSTRUCTION_KEY)
                        .get());
        if (activeConstruction.hasHintsScheme()) {
            signingContext.setConstruction(activeConstruction);
        }
    }
}
