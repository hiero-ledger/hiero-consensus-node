// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.schemas;

import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.service.networkadmin.FreezeService;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Initial mod-service schema for the {@link FreezeService}.
 */
public class V0490FreezeSchema extends Schema {

    public static final String UPGRADE_FILE_HASH_KEY = "UPGRADE_FILE_HASH";
    public static final int UPGRADE_FILE_HASH_STATE_ID = SingletonType.FREEZESERVICE_I_UPGRADE_FILE_HASH.protoOrdinal();
    public static final String UPGRADE_FILE_HASH_STATE_LABEL = computeLabel(FreezeService.NAME, UPGRADE_FILE_HASH_KEY);

    public static final String FREEZE_TIME_KEY = "FREEZE_TIME";
    public static final int FREEZE_TIME_STATE_ID = SingletonType.FREEZESERVICE_I_FREEZE_TIME.protoOrdinal();
    public static final String FREEZE_TIME_STATE_LABEL = computeLabel(FreezeService.NAME, FREEZE_TIME_KEY);

    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(49).patch(0).build();

    /**
     * Constructs a new {@link V0490FreezeSchema}.
     */
    public V0490FreezeSchema() {
        super(VERSION);
    }

    @NonNull
    @Override
    @SuppressWarnings("rawtypes")
    public Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.singleton(UPGRADE_FILE_HASH_STATE_ID, UPGRADE_FILE_HASH_KEY, ProtoBytes.PROTOBUF),
                StateDefinition.singleton(FREEZE_TIME_STATE_ID, FREEZE_TIME_KEY, Timestamp.PROTOBUF));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        final var isGenesis = ctx.previousVersion() == null;
        if (isGenesis) {
            final var upgradeFileHashKeyState = ctx.newStates().<ProtoBytes>getSingleton(UPGRADE_FILE_HASH_STATE_ID);
            upgradeFileHashKeyState.put(ProtoBytes.DEFAULT);

            final var freezeTimeKeyState = ctx.newStates().<Timestamp>getSingleton(FREEZE_TIME_STATE_ID);
            freezeTimeKeyState.put(Timestamp.DEFAULT);
        }
    }
}
