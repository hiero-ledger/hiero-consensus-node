// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.history.schemas.V059HistorySchema.ACTIVE_PROOF_CONSTRUCTION_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.history.ConstructionNodeId;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.WrapsMessageHistory;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.config.data.TssConfig;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Registers the {@code WRAPS_MESSAGE_HISTORIES} state needed for the {@link com.hedera.node.app.history.HistoryService}.
 * <p>
 * The state is a key/value map from {@link ConstructionNodeId} to {@link WrapsMessageHistory},
 * as specified by the {@code HistoryService_I_WRAPS_MESSAGE_HISTORIES} sentinel in
 * {@code virtual_map_state.proto}.
 */
public class V069HistorySchema extends Schema<SemanticVersion> {
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().minor(69).build();

    private static final long MAX_WRAPS_MESSAGE_HISTORIES = 1L << 21;

    public static final String WRAPS_MESSAGE_HISTORIES_KEY = "WRAPS_MESSAGE_HISTORIES";
    public static final int WRAPS_MESSAGE_HISTORIES_STATE_ID =
            StateKey.KeyOneOfType.HISTORYSERVICE_I_WRAPS_MESSAGE_HISTORIES.protoOrdinal();

    private final HistoryService historyService;

    public V069HistorySchema(@NonNull final HistoryService historyService) {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
        this.historyService = requireNonNull(historyService);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.onDisk(
                WRAPS_MESSAGE_HISTORIES_STATE_ID,
                WRAPS_MESSAGE_HISTORIES_KEY,
                ConstructionNodeId.PROTOBUF,
                WrapsMessageHistory.PROTOBUF,
                MAX_WRAPS_MESSAGE_HISTORIES));
    }

    @Override
    public void restart(@NonNull final MigrationContext ctx) {
        if (!ctx.isGenesis() && ctx.appConfig().getConfigData(TssConfig.class).historyEnabled()) {
            final var activeConstruction = requireNonNull(ctx.newStates()
                    .<HistoryProofConstruction>getSingleton(ACTIVE_PROOF_CONSTRUCTION_STATE_ID)
                    .get());
            if (activeConstruction.hasTargetProof()) {
                historyService.setLatestHistoryProof(activeConstruction.targetProofOrThrow());
            }
        }
    }
}
