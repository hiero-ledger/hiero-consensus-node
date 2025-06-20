// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("entities")
public record EntitiesConfig(
        @ConfigProperty(defaultValue = "8000001") @NetworkProperty long maxLifetime,
        // @ConfigProperty(defaultValue = "FILE") Set<EntityType> systemDeletable
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean limitTokenAssociations,
        @ConfigProperty(defaultValue = "true") @NetworkProperty boolean unlimitedAutoAssociationsEnabled) {}
