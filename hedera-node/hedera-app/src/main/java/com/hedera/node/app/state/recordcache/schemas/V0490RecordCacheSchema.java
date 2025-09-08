// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache.schemas;

import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.recordcache.TransactionReceiptEntries;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.state.recordcache.RecordCacheService;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

public class V0490RecordCacheSchema extends Schema {

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
        super(VERSION);
    }

    @NonNull
    @Override
    @SuppressWarnings("rawtypes")
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.queue(
                TRANSACTION_RECEIPTS_STATE_ID, TRANSACTION_RECEIPTS_KEY, TransactionReceiptEntries.PROTOBUF));
    }
}
