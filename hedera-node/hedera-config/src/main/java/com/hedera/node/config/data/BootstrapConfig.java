// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("bootstrap")
public record BootstrapConfig(
        @ConfigProperty(value = "feeSchedulesJson.resource", defaultValue = "genesis/feeSchedules.json")
                @NetworkProperty
                String feeSchedulesJsonResource,
        @ConfigProperty(value = "simpleFeesSchedulesJson.resource", defaultValue = "genesis/simpleFeesSchedules.json")
                @NetworkProperty
                String simpleFeesSchedulesJsonResource,
        @ConfigProperty(
                        value = "genesisPublicKey",
                        defaultValue = "0aa8e21064c61eab86e2a9c164565b4e7a9a4146106e0a6cd03a8c395a110e92")
                @NetworkProperty
                Bytes genesisPublicKey,
        @ConfigProperty(value = "hapiPermissions.path", defaultValue = "data/config/api-permission.properties")
                @NodeProperty
                String hapiPermissionsPath,
        @ConfigProperty(value = "networkProperties.path", defaultValue = "data/config/application.properties")
                @NodeProperty
                String networkPropertiesPath,
        @ConfigProperty(value = "nodeAdminKeys.path", defaultValue = "data/config/node-admin-keys.json") @NodeProperty
                String nodeAdminKeysPath,
        @ConfigProperty(value = "rates.currentHbarEquiv", defaultValue = "1") @NetworkProperty
                int ratesCurrentHbarEquiv,
        @ConfigProperty(value = "rates.currentCentEquiv", defaultValue = "12") @NetworkProperty
                int ratesCurrentCentEquiv,
        @ConfigProperty(value = "rates.currentExpiry", defaultValue = "4102444800") @NetworkProperty
                long ratesCurrentExpiry,
        @ConfigProperty(value = "rates.nextHbarEquiv", defaultValue = "1") @NetworkProperty int ratesNextHbarEquiv,
        @ConfigProperty(value = "rates.nextCentEquiv", defaultValue = "15") @NetworkProperty int ratesNextCentEquiv,
        @ConfigProperty(value = "rates.nextExpiry", defaultValue = "4102444800") @NetworkProperty long ratesNextExpiry,
        @ConfigProperty(value = "system.entityExpiry", defaultValue = "1812637686") @NetworkProperty
                long systemEntityExpiry,
        @ConfigProperty(value = "throttleDefsJson.resource", defaultValue = "genesis/throttles.json") @NodeProperty
                String throttleDefsJsonResource,
        @ConfigProperty(value = "throttleDefsJson.file", defaultValue = "data/config/throttles.json") @NodeProperty
                String throttleDefsJsonFile) {}
