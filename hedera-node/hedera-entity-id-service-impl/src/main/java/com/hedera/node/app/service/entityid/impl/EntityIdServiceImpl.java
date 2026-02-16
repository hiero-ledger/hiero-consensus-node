// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid.impl;

import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema;
import com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema;
import com.hedera.node.config.data.HederaConfig;
import com.swirlds.config.api.Configuration;
import com.hedera.node.app.service.entityid.impl.schemas.V0700EntityIdSchema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Service for providing incrementing entity id numbers. It stores the most recent entity id in state.
 */
public class EntityIdServiceImpl extends EntityIdService {
    private static final Logger log = LogManager.getLogger(EntityIdServiceImpl.class);

    /** {@inheritDoc} */
    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0490EntityIdSchema());
        registry.register(new V0590EntityIdSchema());
        registry.register(new V0700EntityIdSchema());
    }

    @Override
    public boolean doGenesisSetup(
            @NonNull final WritableStates writableStates, @NonNull final Configuration configuration) {
        requireNonNull(writableStates);
        requireNonNull(configuration);
        final var hederaConfig = configuration.getConfigData(HederaConfig.class);
        final long entityNum = hederaConfig.firstUserEntity() - 1;
        log.info("Setting initial entity id to {}", entityNum);
        writableStates.<EntityNumber>getSingleton(ENTITY_ID_STATE_ID).put(new EntityNumber(entityNum));
        // And initialize entity counts to zeros
        writableStates.<EntityCounts>getSingleton(ENTITY_COUNTS_STATE_ID).put(EntityCounts.DEFAULT);
        return true;
    }
}
