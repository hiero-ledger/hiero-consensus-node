package org.hiero.telemetryconverter.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("tracing.client")
public record TraceClientConfig(
        @ConfigProperty String url,
        @ConfigProperty long readTimeoutSeconds
) {}