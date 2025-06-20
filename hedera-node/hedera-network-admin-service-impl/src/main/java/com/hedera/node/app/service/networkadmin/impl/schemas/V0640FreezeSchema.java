// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.schemas;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockstream.FreezeInfo;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.service.networkadmin.FreezeService;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Initial mod-service schema for the {@link FreezeService}.
 */
public class V0640FreezeSchema extends Schema {
    private static final Logger LOGGER = LogManager.getLogger(V0640FreezeSchema.class);

    public static final String UPGRADE_FILE_HASH_KEY = "UPGRADE_FILE_HASH";
    public static final String FREEZE_TIME_KEY = "FREEZE_TIME";
    public static final String FREEZE_INFO_KEY = "FREEZE_INFO";

    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(64).patch(0).build();

    /**
     * Constructs a new {@link V0640FreezeSchema}.
     */
    public V0640FreezeSchema() {
        super(VERSION);
    }

    @NonNull
    @Override
    @SuppressWarnings("rawtypes")
    public Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.singleton(UPGRADE_FILE_HASH_KEY, ProtoBytes.PROTOBUF),
                StateDefinition.singleton(FREEZE_TIME_KEY, Timestamp.PROTOBUF),
                StateDefinition.singleton(FREEZE_INFO_KEY, FreezeInfo.PROTOBUF));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        final var lastFreezeRoundKeyState = ctx.newStates().<FreezeInfo>getSingleton(FREEZE_INFO_KEY);
        if (ctx.isGenesis()) {
            final var upgradeFileHashKeyState = ctx.newStates().<ProtoBytes>getSingleton(UPGRADE_FILE_HASH_KEY);
            upgradeFileHashKeyState.put(ProtoBytes.DEFAULT);

            final var freezeTimeKeyState = ctx.newStates().<Timestamp>getSingleton(FREEZE_TIME_KEY);
            freezeTimeKeyState.put(Timestamp.DEFAULT);

            lastFreezeRoundKeyState.put(
                    FreezeInfo.newBuilder().lastFreezeRound(0L).build());
        } else {
            LOGGER.info(
                    "V0640FreezeSchema is being applied during an upgrade and FreezeInfo is not set. "
                            + "Setting last freeze round to the current round number: {}.",
                    ctx.roundNumber());
            lastFreezeRoundKeyState.put(
                    FreezeInfo.newBuilder().lastFreezeRound(ctx.roundNumber()).build());
        }
    }
}
