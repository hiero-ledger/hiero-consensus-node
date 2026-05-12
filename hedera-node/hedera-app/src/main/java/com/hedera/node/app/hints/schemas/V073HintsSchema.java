// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.node.app.hints.impl.HintsContext;
import com.hedera.node.config.data.TssConfig;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Rehydrates hinTS in-memory state on non-genesis restart if it is available.
 */
public class V073HintsSchema extends Schema<SemanticVersion> {
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(73).patch(0).build();

    private final HintsContext signingContext;

    public V073HintsSchema(@NonNull final HintsContext signingContext) {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
        this.signingContext = requireNonNull(signingContext);
    }

    @Override
    public void restart(@NonNull final MigrationContext ctx) {
        if (ctx.isGenesis() || !ctx.appConfig().getConfigData(TssConfig.class).hintsEnabled()) {
            return;
        }
        final var activeConstruction = ctx.newStates()
                .<HintsConstruction>getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID)
                .get();
        if (activeConstruction != null && activeConstruction.hasHintsScheme()) {
            signingContext.setConstruction(activeConstruction);
        }
    }
}
