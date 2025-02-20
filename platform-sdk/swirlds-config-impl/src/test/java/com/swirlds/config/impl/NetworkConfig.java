// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.annotation.ConstraintMethod;
import com.swirlds.config.api.validation.annotation.Min;
import com.swirlds.config.impl.validators.DefaultConfigViolation;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@ConfigData("network")
public record NetworkConfig(
        @Min(1) int port,
        @ConstraintMethod("checkServer") @ConfigProperty(defaultValue = "localhost") String server,
        @ConfigProperty(value = "errorCodes", defaultValue = "404,500") List<Integer> errorCodes,
        @ConfigProperty(value = "errorCodes", defaultValue = "404,500") Set<Long> errorCodeSet) {

    public ConfigViolation checkServer(final Configuration configuration) {
        if (Objects.equals("invalid", server)) {
            return new DefaultConfigViolation("network.server", server, true, "server must not be invalid");
        }
        return null;
    }
}
