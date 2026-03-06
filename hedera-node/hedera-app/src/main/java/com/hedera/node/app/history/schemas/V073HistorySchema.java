// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.history.HistoryService;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Registers the expected WRAPS proving key hash singleton state for the {@link HistoryService}.
 */
public class V073HistorySchema extends Schema<SemanticVersion> {
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().minor(72).build();

    public static final String WRAPS_PROVING_KEY_HASH_KEY = "WRAPS_PROVING_KEY_HASH";
    public static final int WRAPS_PROVING_KEY_HASH_STATE_ID =
            SingletonType.HISTORYSERVICE_I_WRAPS_PROVING_KEY_HASH.protoOrdinal();

    public V073HistorySchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.singleton(
                WRAPS_PROVING_KEY_HASH_STATE_ID, WRAPS_PROVING_KEY_HASH_KEY, ProtoBytes.PROTOBUF));
    }
}
