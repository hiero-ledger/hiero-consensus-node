// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.config;

import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.net.InetAddress;
import java.util.Objects;

public class NetworkEndpointConverter implements ConfigConverter<NetworkEndpoint> {
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper(new YAMLFactory()).enable(Feature.INCLUDE_SOURCE_IN_LOCATION);

    @Nullable
    @Override
    public NetworkEndpoint convert(@NonNull final String value) throws IllegalArgumentException, NullPointerException {
        Objects.requireNonNull(value, "value must not be null");
        try {
            final JsonNode jsonNode = OBJECT_MAPPER.readTree(value);
            return new NetworkEndpoint(
                    jsonNode.get("nodeId").asLong(),
                    InetAddress.getByName(jsonNode.get("hostname").asText()),
                    jsonNode.get("port").asInt());
        } catch (Exception e) {
            throw new IllegalArgumentException("Parsing InterfaceBinding failed", e);
        }
    }
}
