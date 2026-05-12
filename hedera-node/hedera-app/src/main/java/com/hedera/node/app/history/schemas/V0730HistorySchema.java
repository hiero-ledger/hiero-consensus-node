// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.history.schemas.V071HistorySchema.ACTIVE_PROOF_CONSTRUCTION_STATE_ID;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.config.data.TssConfig;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Set;

/**
 * Registers the expected WRAPS proving key hash singleton state for the {@link HistoryService}.
 */
public class V0730HistorySchema extends Schema<SemanticVersion> {
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(73).patch(0).build();

    public static final String WRAPS_PROVING_KEY_HASH_KEY = "WRAPS_PROVING_KEY_HASH";
    public static final int WRAPS_PROVING_KEY_HASH_STATE_ID =
            SingletonType.HISTORYSERVICE_I_WRAPS_PROVING_KEY_HASH.protoOrdinal();

    private final HistoryService historyService;

    public V0730HistorySchema(@NonNull final HistoryService historyService) {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
        this.historyService = Objects.requireNonNull(historyService);
    }

    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.singleton(
                WRAPS_PROVING_KEY_HASH_STATE_ID, WRAPS_PROVING_KEY_HASH_KEY, ProtoBytes.PROTOBUF));
    }

    @Override
    public void restart(@NonNull final MigrationContext ctx) {
        if (!ctx.isGenesis() && ctx.appConfig().getConfigData(TssConfig.class).historyEnabled()) {
            final var activeConstruction = ctx.newStates()
                    .<HistoryProofConstruction>getSingleton(ACTIVE_PROOF_CONSTRUCTION_STATE_ID)
                    .get();
            if (activeConstruction != null && activeConstruction.hasTargetProof()) {
                historyService.setLatestHistoryProof(activeConstruction.targetProofOrThrow());
            }
        }
    }
}
