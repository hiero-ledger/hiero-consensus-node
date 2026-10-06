// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.history.schemas.V0730HistorySchema.WRAPS_PROVING_KEY_HASH_STATE_ID;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.history.HistoryService;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Removes the WRAPS proving key hash singleton from the {@link HistoryService} state, since the TSS library
 * now embeds the public parameters it uses to construct and verify WRAPS proofs; so there is no longer any
 * proving key for nodes to download and verify.
 */
public class V0790HistorySchema extends Schema<SemanticVersion> {
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(79).patch(0).build();

    public V0790HistorySchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public @NonNull Set<Integer> statesToRemove() {
        return Set.of(WRAPS_PROVING_KEY_HASH_STATE_ID);
    }
}
