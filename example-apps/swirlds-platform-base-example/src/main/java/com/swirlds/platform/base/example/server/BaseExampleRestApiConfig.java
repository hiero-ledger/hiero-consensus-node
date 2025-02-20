// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.server;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("base.example.server")
public record BaseExampleRestApiConfig(
        @ConfigProperty(defaultValue = "localhost") String host,
        int port,
        String basePath,
        @ConfigProperty(defaultValue = "true") boolean banner) {}
