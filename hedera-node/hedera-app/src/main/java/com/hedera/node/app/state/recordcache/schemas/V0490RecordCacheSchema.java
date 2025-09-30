// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.recordcache.TransactionReceiptEntries;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.state.recordcache.RecordCacheService;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Defines the record cache schema for v0.49.0, registering the transaction receipts
 * queue used to persist recent transaction outcomes.
 */
public class V0490RecordCacheSchema extends Schema<SemanticVersion> {

    public static final String TRANSACTION_RECEIPTS_KEY = "TRANSACTION_RECEIPTS";
    public static final int TRANSACTION_RECEIPTS_STATE_ID =
            StateKey.KeyOneOfType.RECORDCACHE_I_TRANSACTION_RECEIPTS.protoOrdinal();
    public static final String TRANSACTION_RECEIPTS_STATE_LABEL =
            computeLabel(RecordCacheService.NAME, TRANSACTION_RECEIPTS_KEY);

    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(49).patch(0).build();

    public V0490RecordCacheSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @NonNull
    @Override
    @SuppressWarnings("rawtypes")
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.queue(
                TRANSACTION_RECEIPTS_STATE_ID, TRANSACTION_RECEIPTS_KEY, TransactionReceiptEntries.PROTOBUF));
    }
}
