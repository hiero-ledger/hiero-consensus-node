// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.PlatformState;
import com.swirlds.platform.state.service.WritablePlatformStateStore;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Defines the {@link PlatformState} migration for V0640 which will set the last freeze round to the migration round.
 */
public class V0640PlatformStateSchema extends Schema<SemanticVersion> {
    private static final Logger LOGGER = LogManager.getLogger(V0640PlatformStateSchema.class);
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(64).patch(0).build();

    public V0640PlatformStateSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        if (!ctx.isGenesis()) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "V0640PlatformStateSchema - migrate() called during non-genesis state migration. Setting the "
                                + "last freeze round to the migration round number: {}",
                        ctx.roundNumber());
            }
            final var platformStateStore = new WritablePlatformStateStore(ctx.newStates());
            platformStateStore.setLatestFreezeRound(ctx.roundNumber());
        }
    }
}
