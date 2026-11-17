// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.history.schemas.V0790HistorySchema.HINTS_KEY_PROVENANCE_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitnessRecord;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.history.HistoryService;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Adds durable block-signing witnesses alongside the append-only adoption queue.
 * Historical adoption records are indexed without inventing activation times or
 * signatures, so their witnesses can later be backfilled from historical blocks.
 */
public class V0800HistorySchema extends Schema<SemanticVersion> {
    public static final String HINTS_KEY_WITNESSES_KEY = "HINTS_KEY_WITNESSES";
    public static final int HINTS_KEY_WITNESSES_STATE_ID =
            StateKey.KeyOneOfType.HISTORYSERVICE_I_HINTS_KEY_WITNESSES.protoOrdinal();
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().minor(80).build();

    private final HistoryService historyService;

    public V0800HistorySchema(@NonNull final HistoryService historyService) {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
        this.historyService = requireNonNull(historyService);
    }

    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.keyValue(
                HINTS_KEY_WITNESSES_STATE_ID,
                HINTS_KEY_WITNESSES_KEY,
                ProtoLong.PROTOBUF,
                HintsKeyWitnessRecord.PROTOBUF));
    }

    @Override
    public void restart(@NonNull final MigrationContext ctx) {
        requireNonNull(ctx);
        // Only the latest schema's restart hook runs.
        new V0790HistorySchema(historyService).restart(ctx);
        if (ctx.isGenesis()) {
            return;
        }
        final var states = ctx.newStates();
        final var witnesses = states.<ProtoLong, HintsKeyWitnessRecord>get(HINTS_KEY_WITNESSES_STATE_ID);
        final var provenance = states.<HintsKeyProvenance>getQueue(HINTS_KEY_PROVENANCE_STATE_ID)
                .iterator();
        while (provenance.hasNext()) {
            final var link = provenance.next();
            final var key = new ProtoLong(link.targetHintsConstructionId());
            final var existing = witnesses.get(key);
            if (existing == null) {
                witnesses.put(
                        key, HintsKeyWitnessRecord.newBuilder().provenance(link).build());
            } else if (!link.equals(existing.provenance())) {
                throw new IllegalStateException("Witness record conflicts with adopted key provenance");
            }
        }
    }
}
