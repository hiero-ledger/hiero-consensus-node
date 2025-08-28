package org.hiero.telemetryconverter.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

import java.nio.file.Path;

@ConfigData("tracing.converter")
public record ConverterConfig(
        @ConfigProperty(defaultValue = "./../hedera-node/test-clients/build/hapi-test") Path jfrDirectory,
        @ConfigProperty(defaultValue = "./../hedera-node/test-clients/build/hapi-test/node0/data/blockStreams") Path blockStreamsDirectory
) {}