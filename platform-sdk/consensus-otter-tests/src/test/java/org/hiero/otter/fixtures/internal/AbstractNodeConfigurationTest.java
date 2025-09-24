// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.gossip.config.NetworkEndpoint;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the {@link AbstractNodeConfiguration} class.
 */
public class AbstractNodeConfigurationTest {

    private LifeCycle lifeCycle;

    private NodeConfiguration subject;

    @BeforeEach
    void setUp() {
        lifeCycle = LifeCycle.INIT;
        subject = new TestNodeConfiguration(() -> lifeCycle);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testBooleanProperty(final boolean value) {
        subject.set("myBooleanValue", value);
        final Configuration config = subject.current();
        final TestConfigData configData = config.getConfigData(TestConfigData.class);
        assertThat(configData.myBooleanValue()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "hello world", "", "äöüÄÖÜß", " \t\n\"", "特殊字符", "emoji 😊"})
    void testStringProperty(final String value) {
        subject.set("myStringValue", value);
        final Configuration config = subject.current();
        final TestConfigData configData = config.getConfigData(TestConfigData.class);
        assertThat(configData.myStringValue()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 42, -100, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void testIntProperty(final int value) {
        subject.set("myIntValue", value);
        final Configuration config = subject.current();
        final TestConfigData configData = config.getConfigData(TestConfigData.class);
        assertThat(configData.myIntValue()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(
            doubles = {
                0.0,
                3.14,
                -99.99,
                Double.MIN_VALUE,
                Double.MAX_VALUE,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY
            })
    void testDoubleProperty(final double value) {
        subject.set("myDoubleValue", value);
        final Configuration config = subject.current();
        final TestConfigData configData = config.getConfigData(TestConfigData.class);
        assertThat(configData.myDoubleValue()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 987654321L, -1000L, Long.MIN_VALUE, Long.MAX_VALUE})
    void testLongProperty(final long value) {
        subject.set("myLongValue", value);
        final Configuration config = subject.current();
        final TestConfigData configData = config.getConfigData(TestConfigData.class);
        assertThat(configData.myLongValue()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "REPLAYING_EVENTS", "FREEZE_COMPLETE"})
    void testEnumProperty(final String value) {
        final PlatformStatus status = PlatformStatus.valueOf(value);
        subject.set("myEnumValue", status);
        final Configuration config = subject.current();
        final TestConfigData configData = config.getConfigData(TestConfigData.class);
        assertThat(configData.myEnumValue()).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT15M", "PT1H", "PT30S", "P1D", "PT0S", "P2DT3H4M"})
    void testDurationProperty(final String value) {
        final Duration duration = Duration.parse(value);
        subject.set("myDurationValue", duration);
        final Configuration config = subject.current();
        final TestConfigData configData = config.getConfigData(TestConfigData.class);
        assertThat(configData.myDurationValue()).isEqualTo(duration);
    }

    @ParameterizedTest
    @ValueSource(strings = {"foo,bar,baz", "single", ""})
    void testStringListProperty(final String value) {
        final List<String> list = value.isEmpty() ? List.of() : List.of(value.split(","));
        subject.setStrings("myStringList", list);
        final Configuration config = subject.current();
        final TestConfigData configData = config.getConfigData(TestConfigData.class);
        assertThat(configData.myStringList()).isEqualTo(list);
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

    @ParameterizedTest
    @MethodSource("taskSchedulerConfigurationProvider")
    void testTaskSchedulerConfigurationProperty(final TaskSchedulerConfiguration schedulerConfig) {
        subject.set("mySchedulerConfiguration", schedulerConfig);
        final Configuration config = subject.current();
        final TestConfigData configData = config.getConfigData(TestConfigData.class);

        // Use custom assertion to verify the configuration
        TaskSchedulerConfigurationAssert.assertThat(configData.mySchedulerConfiguration())
                .isEqualTo(schedulerConfig);
    }

    private static Stream<Arguments> taskSchedulerConfigurationProvider() {
        return Stream.of(
                // SEQUENTIAL with minimal configuration
                Arguments.of(
                        new TaskSchedulerConfiguration(TaskSchedulerType.SEQUENTIAL, null, null, null, null, null)),
                // SEQUENTIAL with all features enabled
                Arguments.of(
                        new TaskSchedulerConfiguration(TaskSchedulerType.SEQUENTIAL, 1000L, true, true, true, true)),
                // DIRECT with no capacity limit
                Arguments.of(new TaskSchedulerConfiguration(TaskSchedulerType.DIRECT, 0L, false, false, false, false)),
                // DIRECT with capacity and metrics
                Arguments.of(new TaskSchedulerConfiguration(TaskSchedulerType.DIRECT, 500L, true, true, false, false)),
                // CONCURRENT with mixed features
                Arguments.of(
                        new TaskSchedulerConfiguration(TaskSchedulerType.CONCURRENT, 2000L, false, true, true, false)),
                // CONCURRENT with all features disabled
                Arguments.of(
                        new TaskSchedulerConfiguration(TaskSchedulerType.CONCURRENT, 100L, false, false, false, false)),
                // NO_OP scheduler
                Arguments.of(new TaskSchedulerConfiguration(TaskSchedulerType.NO_OP, null, null, null, null, null)),
                // DIRECT_THREADSAFE with various settings
                Arguments.of(new TaskSchedulerConfiguration(
                        TaskSchedulerType.DIRECT_THREADSAFE, 750L, true, false, false, true)),
                // Test with Long.MAX_VALUE capacity
                Arguments.of(new TaskSchedulerConfiguration(
                        TaskSchedulerType.SEQUENTIAL, Long.MAX_VALUE, true, true, true, true)));
    }

    private static class TestNodeConfiguration extends AbstractNodeConfiguration {

        public TestNodeConfiguration(@NonNull final Supplier<LifeCycle> lifeCycleSupplier) {
            super(lifeCycleSupplier);
        }

        @NonNull
        @Override
        public Configuration current() {
            return new TestConfigBuilder()
                    .withSource(new SimpleConfigSource(overriddenProperties))
                    .withConfigDataType(TestConfigData.class)
                    .getOrCreateConfig();
        }
    }

    /**
     * Test configuration data record with various property types.
     */
    @ConfigData
    public record TestConfigData(
            @ConfigProperty(defaultValue = "") boolean myBooleanValue,
            @ConfigProperty(defaultValue = "") String myStringValue,
            @ConfigProperty(defaultValue = "42") int myIntValue,
            @ConfigProperty(defaultValue = "3.14") double myDoubleValue,
            @ConfigProperty(defaultValue = "987654321") long myLongValue,
            @ConfigProperty(defaultValue = "ACTIVE") PlatformStatus myEnumValue,
            @ConfigProperty(defaultValue = "PT15M") Duration myDurationValue,
            @ConfigProperty(defaultValue = "") List<String> myStringList,
            @ConfigProperty(defaultValue = "") List<NodeId> myNodeIdList,
            @ConfigProperty(defaultValue = "") List<NetworkEndpoint> myEndpointList,
            @ConfigProperty(defaultValue = "") TaskSchedulerConfiguration mySchedulerConfiguration) {}
}
