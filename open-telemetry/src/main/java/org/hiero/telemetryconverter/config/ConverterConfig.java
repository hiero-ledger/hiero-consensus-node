package org.hiero.telemetryconverter.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

import java.nio.file.Path;

@ConfigData("tracing.converter")
public record ConverterConfig(
        @ConfigProperty Path jfrDirectory,
        @ConfigProperty Path blockStreamsDirectory
) {}