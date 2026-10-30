// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.history.schemas.V071HistorySchema.LEDGER_ID_STATE_ID;
import static com.hedera.node.app.history.schemas.V0730HistorySchema.WRAPS_PROVING_KEY_HASH_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.history.HistoryService;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Removes the WRAPS proving key hash singleton from the {@link HistoryService} state, since the TSS library
 * now embeds the public parameters it uses to construct and verify WRAPS proofs; so there is no longer any
 * proving key for nodes to download and verify. Also registers the append-only
 * queue of explicit authorizations between adopted hinTS signing generations.
 */
public class V0790HistorySchema extends Schema<SemanticVersion> {
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(79).patch(0).build();

    public static final String HINTS_KEY_PROVENANCE_KEY = "HINTS_KEY_PROVENANCE";
    public static final int HINTS_KEY_PROVENANCE_STATE_ID =
            StateKey.KeyOneOfType.HISTORYSERVICE_I_HINTS_KEY_PROVENANCE.protoOrdinal();

    private final HistoryService historyService;

    public V0790HistorySchema(@NonNull final HistoryService historyService) {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
        this.historyService = requireNonNull(historyService);
    }

    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.queue(
                HINTS_KEY_PROVENANCE_STATE_ID, HINTS_KEY_PROVENANCE_KEY, HintsKeyProvenance.PROTOBUF));
    }

    @Override
    public void restart(@NonNull final MigrationContext ctx) {
        if (ctx.isGenesis()) {
            return;
        }
        final var ledgerId =
                ctx.newStates().<ProtoBytes>getSingleton(LEDGER_ID_STATE_ID).get();
        if (ledgerId != null
                && ledgerId.value().length() != 0
                && ledgerId.value().length() != 64) {
            throw new IllegalStateException("Legacy history state is incompatible with TSS 3.23.0; "
                    + "a coordinated hinTS/history reset and rebootstrap is required before upgrade. "
                    + "The existing ledger trust root cannot be silently replaced.");
        }
        // Only the latest schema's restart hook runs. Preserve initialization and proof restoration.
        new V0730HistorySchema(historyService).restart(ctx);
    }

    @Override
    public @NonNull Set<Integer> statesToRemove() {
        return Set.of(WRAPS_PROVING_KEY_HASH_STATE_ID);
    }
}
