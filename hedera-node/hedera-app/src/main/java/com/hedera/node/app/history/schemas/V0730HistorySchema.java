// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static org.apache.commons.lang3.StringUtils.isBlank;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
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

    public V0730HistorySchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.singleton(
                WRAPS_PROVING_KEY_HASH_STATE_ID, WRAPS_PROVING_KEY_HASH_KEY, ProtoBytes.PROTOBUF));
    }

    @Override
    public void restart(@NonNull final MigrationContext ctx) {
        if (!ctx.isGenesis()) {
            // Initialize (if needed)
            final var currentHash = ctx.newStates()
                    .<String>getSingleton(WRAPS_PROVING_KEY_HASH_STATE_ID)
                    .get();
            if (currentHash == null) {
                ctx.newStates().getSingleton(WRAPS_PROVING_KEY_HASH_STATE_ID).put(ProtoBytes.DEFAULT);
            }

            // If a configured hash exists, unconditionally accept as valid and put in state
            final var configuredHash =
                    ctx.appConfig().getConfigData(TssConfig.class).wrapsProvingKeyHash();
            if (!isBlank(configuredHash)) {
                ctx.newStates()
                        .getSingleton(WRAPS_PROVING_KEY_HASH_STATE_ID)
                        .put(ProtoBytes.newBuilder()
                                .value(Bytes.fromHex(configuredHash))
                                .build());
            }
        }
    }
}
