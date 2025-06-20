// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.sources.YamlConfigSource;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

public class GossipConfigTest {

    @Test
    void testReadingFile() throws UnknownHostException {
        final Configuration configuration = new TestConfigBuilder()
                .withSource(new YamlConfigSource("node-overrides.yaml"))
                .getOrCreateConfig();
        assertNotNull(configuration);

        final GossipConfig gossipConfig = configuration.getConfigData(GossipConfig.class);
        assertNotNull(gossipConfig);

        assertNotNull(gossipConfig.interfaceBindings());
        assertEquals(4, gossipConfig.interfaceBindings().size());
        assertEquals(4, gossipConfig.endpointOverrides().size());

        final NetworkEndpoint endpoint = gossipConfig.interfaceBindings().getFirst();
        assertEquals(0, endpoint.nodeId());
        assertEquals(InetAddress.getByName("10.10.10.1"), endpoint.hostname());
        assertEquals(1234, endpoint.port());

        final NetworkEndpoint override = gossipConfig.endpointOverrides().getFirst();
        assertEquals(5, override.nodeId());
        assertEquals(InetAddress.getByName("10.10.10.11"), override.hostname());
        assertEquals(1238, override.port());
    }
}
