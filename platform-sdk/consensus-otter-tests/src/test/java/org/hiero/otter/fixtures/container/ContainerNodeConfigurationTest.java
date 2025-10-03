// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.gossip.config.NetworkEndpoint;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ContainerNodeConfigurationTest {

    private LifeCycle lifeCycle;

    private ContainerNodeConfiguration subject;

    @BeforeEach
    void setUp() {
        lifeCycle = LifeCycle.INIT;
        subject = new TestNodeConfiguration(() -> lifeCycle);
    }

    @ParameterizedTest
    @MethodSource("networkEndpointListProvider")
    void testNetworkEndpointListProperty(final List<NetworkEndpoint> endpoints) {
        subject.setNetworkEndpoints("myEndpointList", endpoints);
        final Configuration config = subject.current();
        final TestConfigData configData = config.getConfigData(TestConfigData.class);
        assertThat(configData.myEndpointList()).isEqualTo(endpoints);
    }

    private static Stream<Arguments> networkEndpointListProvider() throws UnknownHostException {
        return Stream.of(
                // Empty list
                Arguments.of(List.of()),
                // Single endpoint with node ID 42
                Arguments.of(List.of(new NetworkEndpoint(42L, InetAddress.getByName("192.168.1.1"), 8080))),
                // Multiple endpoints with various node IDs and ports
                Arguments.of(List.of(
                        new NetworkEndpoint(100L, InetAddress.getByName("172.16.0.1"), 8080),
                        new NetworkEndpoint(200L, InetAddress.getByName("172.16.0.2"), 9090),
                        new NetworkEndpoint(300L, InetAddress.getByName("172.16.0.3"), 7070),
                        new NetworkEndpoint(999L, InetAddress.getByName("172.16.0.4"), 6060))));
    }

    private static class TestNodeConfiguration extends ContainerNodeConfiguration {

        public TestNodeConfiguration(@NonNull final Supplier<LifeCycle> lifeCycleSupplier) {
            super(lifeCycleSupplier);
        }

        @NonNull
        @Override
        public Configuration current() {
            return new TestConfigBuilder()
                    .withSource(new SimpleConfigSource(overriddenProperties))
                    .withConfigDataType(ContainerNodeConfigurationTest.TestConfigData.class)
                    .getOrCreateConfig();
        }
    }

    /**
     * Test configuration data record with various property types.
     */
    @ConfigData
    public record TestConfigData(@ConfigProperty(defaultValue = "") List<NetworkEndpoint> myEndpointList) {}
}
