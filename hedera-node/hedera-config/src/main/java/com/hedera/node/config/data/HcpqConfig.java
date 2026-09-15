// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/** Network-controlled activation and resource limits for HCPQ signatures. */
@ConfigData("hcpq")
public record HcpqConfig(
        @ConfigProperty(defaultValue = "false") @NetworkProperty
        boolean enabled,

        @ConfigProperty(defaultValue = "1") @NetworkProperty int maxSignaturesPerTransaction) {}
