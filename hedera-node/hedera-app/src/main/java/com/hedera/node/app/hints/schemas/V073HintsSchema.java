// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.config.data.TssConfig;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Initializes hinTS singleton states on non-genesis restart if they are absent.
 */
public class V073HintsSchema extends Schema<SemanticVersion> {
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(73).patch(0).build();

    private final HintsLibrary library;

    public V073HintsSchema(@NonNull final HintsLibrary library) {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
        this.library = requireNonNull(library);
    }

    @Override
    public void restart(@NonNull final MigrationContext ctx) {
        if (ctx.isGenesis() || !ctx.appConfig().getConfigData(TssConfig.class).hintsEnabled()) {
            return;
        }
        final var writableStates = ctx.newStates();
        if (writableStates
                        .<HintsConstruction>getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID)
                        .get()
                == null) {
            writableStates
                    .<HintsConstruction>getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID)
                    .put(HintsConstruction.DEFAULT);
        }
        if (writableStates
                        .<HintsConstruction>getSingleton(NEXT_HINTS_CONSTRUCTION_STATE_ID)
                        .get()
                == null) {
            writableStates
                    .<HintsConstruction>getSingleton(NEXT_HINTS_CONSTRUCTION_STATE_ID)
                    .put(HintsConstruction.DEFAULT);
        }
    }
}
