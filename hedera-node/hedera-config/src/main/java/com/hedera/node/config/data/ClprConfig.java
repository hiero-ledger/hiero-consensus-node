// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configuration for the CLPR interledger communication protocol.
 *
 * @param clprEnabled whether to enable the CLPR interledger communication protocol
 * @param connectionFrequency the frequency at which connections are made to other ledgers, in milliseconds
 * @param publicizeNetworkAddresses whether the node should advertise its CLPR endpoint network addresses.
 */
@ConfigData("clpr")
public record ClprConfig(
        @ConfigProperty(defaultValue = "false") @NetworkProperty
        boolean clprEnabled,

        @ConfigProperty(defaultValue = "5000") @NetworkProperty
        int connectionFrequency,

        @ConfigProperty(defaultValue = "true") @NetworkProperty
        boolean publicizeNetworkAddresses,

        @ConfigProperty(defaultValue = "5") @NetworkProperty int maxBundleMessages,

        @ConfigProperty(defaultValue = "6144") @NetworkProperty
        int maxBundleBytes) {}
