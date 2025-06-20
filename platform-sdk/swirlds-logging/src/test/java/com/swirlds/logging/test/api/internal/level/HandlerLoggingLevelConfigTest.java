// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.test.api.internal.level;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Marker;
import com.swirlds.logging.api.internal.configuration.ConfigLevelConverter;
import com.swirlds.logging.api.internal.configuration.MarkerStateConverter;
import com.swirlds.logging.api.internal.level.ConfigLevel;
import com.swirlds.logging.api.internal.level.HandlerLoggingLevelConfig;
import com.swirlds.logging.api.internal.level.MarkerState;
import com.swirlds.logging.test.fixtures.util.LoggingTestUtils;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class HandlerLoggingLevelConfigTest {

    @Test
    void testConstructorExceptions() {
        Assertions.assertThrows(NullPointerException.class, () -> HandlerLoggingLevelConfig.create(null));
        Assertions.assertThrows(
                NullPointerException.class, () -> HandlerLoggingLevelConfig.create(null, (String) null));
    }

    @Test
    void testConstructor() {
        // given
        final HandlerLoggingLevelConfig config = HandlerLoggingLevelConfig.create(
                LoggingTestUtils.getConfigBuilder().getOrCreateConfig());

        // then
        checkDefaultBehavior(config);
    }

    @Test
    void testWithDifferentPrefix() {
        // given
        final HandlerLoggingLevelConfig config = HandlerLoggingLevelConfig.create(
                LoggingTestUtils.getConfigBuilder().getOrCreateConfig(), "test.prefix");

        // then
        checkDefaultBehavior(config);
    }

    @Test
    void testWithDifferentPrefixAndLevel() {
        // given
        final Configuration configuration = LoggingTestUtils.getConfigBuilder()
                .withValue("logging.level", "ERROR")
                .getOrCreateConfig();
        final HandlerLoggingLevelConfig config = HandlerLoggingLevelConfig.create(configuration, "test.prefix");

        // then
        checkEnabledForLevel(config, "", Level.ERROR);
        checkEnabledForLevel(config, "a.long.name.for.a.logger", Level.ERROR);
        checkEnabledForLevel(config, "com.sample", Level.ERROR);
        checkEnabledForLevel(config, "com.sample.Class", Level.ERROR);
        checkEnabledForLevel(config, "com.sample.package", Level.ERROR);
        checkEnabledForLevel(config, "com.sample.package.ClassA", Level.ERROR);
        checkEnabledForLevel(config, "com.sample.package.ClassB", Level.ERROR);
        checkEnabledForLevel(config, "com.sample.package.ClassC", Level.ERROR);
    }

    @Test
    void testWithConfigPerClass() {
        // given
        final Configuration configuration = LoggingTestUtils.getConfigBuilder()
                .withValue("logging.level", "INFO")
                .withValue("logging.level.com.sample", "DEBUG")
                .withValue("logging.level.com.sample.package", "ERROR")
                .withValue("logging.level.com.sample.package.ClassB", "INFO")
                .withValue("logging.level.com.sample.package.ClassC", "TRACE")
                .withValue("logging.level.com.sample.package.ClassCD", "DEBUG")
                .getOrCreateConfig();
        final HandlerLoggingLevelConfig config = HandlerLoggingLevelConfig.create(configuration);

        // then
        checkEnabledForLevel(config, "", Level.INFO);
        checkEnabledForLevel(config, "a.long.name.for.a.logger", Level.INFO);
        checkEnabledForLevel(config, "com.sample.Class", Level.DEBUG);
        checkEnabledForLevel(config, "com.sample.package.ClassA", Level.ERROR);
        checkEnabledForLevel(config, "com.sample.package.ClassB", Level.INFO);
        checkEnabledForLevel(config, "com.sample.package.ClassC", Level.TRACE);
        checkEnabledForLevel(config, "com.sample.package.ClassCD", Level.DEBUG);
    }

    @Test
    void testPackagesWithSimilarNames() {
        // given
        final Configuration configuration = LoggingTestUtils.getConfigBuilder()
                .withValue("logging.level", "INFO")
                .withValue("logging.level.com.sample", "DEBUG")
                .getOrCreateConfig();
        final HandlerLoggingLevelConfig config = HandlerLoggingLevelConfig.create(configuration);

        // then
        checkEnabledForLevel(config, "", Level.INFO);
        checkEnabledForLevel(config, "a.long.name.for.a.logger", Level.INFO);
        checkEnabledForLevel(config, "com.sample.Bla", Level.DEBUG);
        checkEnabledForLevel(config, "com.samples.Class", Level.INFO);
    }

    private static void checkEnabledForLevel(HandlerLoggingLevelConfig config, String name, Level level) {
        Stream.of(Level.values())
                .filter(level::enabledLoggingOfLevel)
                .forEach(l -> Assertions.assertTrue(
                        config.isEnabled(name, l),
                        "%s should be enabled for package '%s' with level %s".formatted(l, name, level)));
        Stream.of(Level.values())
                .filter(l -> !level.enabledLoggingOfLevel(l))
                .forEach(l -> Assertions.assertFalse(
                        config.isEnabled(name, l),
                        "%s should not be enabled for package '%s' with level %s".formatted(l, name, level)));
    }

    @Test
    void testWithConfigAndDefaultLevel() {
        // given
        final Configuration configuration = LoggingTestUtils.getConfigBuilder()
                .withValue("logging.level", "WARN")
                .withValue("logging.level.com.sample", "DEBUG")
                .getOrCreateConfig();
        final HandlerLoggingLevelConfig config = HandlerLoggingLevelConfig.create(configuration);

        // then
        checkEnabledForLevel(config, "", Level.WARN);
        checkEnabledForLevel(config, "a.long.name.for.a.logger", Level.WARN);
        checkEnabledForLevel(config, "com.sample.Class", Level.DEBUG);
        checkEnabledForLevel(config, "com.sample.package", Level.DEBUG);
        checkEnabledForLevel(config, "com.sample.package.ClassA", Level.DEBUG);
        checkEnabledForLevel(config, "com.sample.package.ClassB", Level.DEBUG);
        checkEnabledForLevel(config, "com.sample.package.ClassC", Level.DEBUG);
    }

    @Test
    void testWithConfigAndDefaultLevelAndPrefix() {
        // given
        final Configuration configuration = LoggingTestUtils.getConfigBuilder()
                .withValue("logging.level.com.sample", "DEBUG")
                .getOrCreateConfig();
        final HandlerLoggingLevelConfig config = HandlerLoggingLevelConfig.create(configuration, "test.logging.level");

        // then
        checkEnabledForLevel(config, "", Level.INFO);
        checkEnabledForLevel(config, "a.long.name.for.a.logger", Level.INFO);
        checkEnabledForLevel(config, "com.sample.Class", Level.DEBUG);
        checkEnabledForLevel(config, "com.sample.package", Level.DEBUG);
        checkEnabledForLevel(config, "com.sample.package.ClassA", Level.DEBUG);
        checkEnabledForLevel(config, "com.sample.package.ClassB", Level.DEBUG);
        checkEnabledForLevel(config, "com.sample.package.ClassC", Level.DEBUG);
    }

    private static void checkDefaultBehavior(HandlerLoggingLevelConfig config) {
        checkEnabledForLevel(config, "", Level.INFO);
        checkEnabledForLevel(config, "a.long.name.for.a.logger", Level.INFO);
        checkEnabledForLevel(config, "com.sample", Level.INFO);
        checkEnabledForLevel(config, "com.sample.Class", Level.INFO);
        checkEnabledForLevel(config, "com.sample.package", Level.INFO);
        checkEnabledForLevel(config, "com.sample.package.ClassA", Level.INFO);
        checkEnabledForLevel(config, "com.sample.package.ClassB", Level.INFO);
        checkEnabledForLevel(config, "com.sample.package.ClassC", Level.INFO);
    }

    @Test
    void testPackageShouldOverrideDefaultLevel() {
        // given
        final Configuration configuration = LoggingTestUtils.getConfigBuilder()
                .withValue("logging.level", Level.WARN)
                .withValue("logging.level.com.sample", Level.INFO)
                .getOrCreateConfig();
        final HandlerLoggingLevelConfig config = HandlerLoggingLevelConfig.create(configuration, "test.logging.level");

        // then
        checkEnabledForLevel(config, "com.sample.Foo", Level.INFO);
    }

    @Test
    void testClassShouldOverrideDefaultLevel() {
        // given
        final Configuration configuration = LoggingTestUtils.getConfigBuilder()
                .withValue("logging.level.com.sample.package.Class", Level.TRACE)
                .getOrCreateConfig();
        final HandlerLoggingLevelConfig config = HandlerLoggingLevelConfig.create(configuration, "test.logging.level");

        // then
        checkEnabledForLevel(config, "com.sample.package.Class", Level.TRACE);
    }

    @Test
    void logLevelWithHandlerOverwrite() {
        // given
        final Configuration configuration = createDefaultBuilder()
                .withValue("logging.level", "ERROR")
                .withValue("logging.level.com.foo", "TRACE")
                .withValue("logging.level.com.bar", "TRACE")
                .withValue("logging.handler.HANDLER1.level.com.bar", "OFF")
                .withValue("logging.handler.HANDLER2.level.com.foo", "OFF")
                .withValue("logging.handler.HANDLER2.level", "INFO")
                .getOrCreateConfig();
        final HandlerLoggingLevelConfig config = HandlerLoggingLevelConfig.create(configuration, "HANDLER1");

        // then
        assertThat(config.isEnabled("com.bar.some.Class", Level.INFO)).isFalse();
        assertThat(config.isEnabled("com.bar.some.Class", Level.ERROR)).isFalse();
        assertThat(config.isEnabled("com.foo.some.Class", Level.TRACE)).isTrue();
        assertThat(config.isEnabled("com.some.Class", Level.ERROR)).isTrue();
        assertThat(config.isEnabled("com.some.Class", Level.INFO)).isFalse();
    }

    @Test
    void logLevelWithHandlerOverwriteWithMarker() {
        // given
        final Marker markerBaz = new Marker("baz");
        final Marker markerBum = new Marker("bum");
        final Marker markerBrum = new Marker("brum");
        final Marker markerPeng = new Marker("peng");
        final Marker combinedMarker = new Marker("bum", markerBaz);

        final Configuration configuration = createDefaultBuilder()
                // Level
                .withValue("logging.level", "ERROR")
                .withValue("logging.level.com.foo", "TRACE")
                .withValue("logging.level.com.bar", "TRACE")
                // Markers
                .withValue("logging.marker.baz", "ENABLED")
                .withValue("logging.marker.bum", "DISABLED")
                .withValue("logging.marker.peng", "UNDEFINED")
                // Handler Markers
                .withValue("logging.handler.HANDLER1.marker.peng", "DISABLED")
                .withValue("logging.handler.HANDLER2.marker.peng", "ENABLED")
                .getOrCreateConfig();
        final HandlerLoggingLevelConfig config = HandlerLoggingLevelConfig.create(configuration, "HANDLER1");

        // then
        assertThat(config.isEnabled("com.bar.some.Class", Level.INFO, markerBaz))
                .isTrue();
        assertThat(config.isEnabled("com.foo.some.Class", Level.TRACE, markerBum))
                .isFalse();
        assertThat(config.isEnabled("com.foo.some.Class", Level.TRACE, combinedMarker))
                .isTrue();
        assertThat(config.isEnabled("com.foo.some.Class", Level.ERROR, markerPeng))
                .isFalse();
        assertThat(config.isEnabled("com.foo.some.Class", Level.TRACE, markerBrum))
                .isTrue();
        assertThat(config.isEnabled("com.some.Class", Level.ERROR, markerBrum)).isTrue();
        assertThat(config.isEnabled("com.some.Class", Level.INFO, markerBrum)).isFalse();
    }

    @Test
    void logLevelWithDifferentHandlers() {
        // given
        final Marker markerA = new Marker("A");
        final Marker markerB = new Marker("B");
        final Marker markerC = new Marker("C");

        final Configuration configuration = createDefaultBuilder()
                .withValue("logging.handler.HANDLER1.marker.A", "ENABLED")
                .withValue("logging.handler.HANDLER2.marker.C", "DISABLED")
                .getOrCreateConfig();

        final HandlerLoggingLevelConfig config1 = HandlerLoggingLevelConfig.create(configuration, "HANDLER1");
        final HandlerLoggingLevelConfig config2 = HandlerLoggingLevelConfig.create(configuration, "HANDLER2");

        // then
        assertThat(config1.isEnabled("com.example.ClassA", Level.INFO, markerA)).isTrue();
        assertThat(config1.isEnabled("com.example.ClassB", Level.ERROR, markerB))
                .isTrue();
        assertThat(config2.isEnabled("com.example.ClassC", Level.WARN, markerC)).isFalse();
        assertThat(config2.isEnabled("com.example.ClassA", Level.INFO, markerC)).isFalse();
    }

    @Test
    void logLevelWithDefaultHandler() {
        // given
        final Marker markerX = new Marker("X");
        final Marker markerY = new Marker("Y");

        final Configuration configuration = createDefaultBuilder()
                .withValue("logging.handler.HANDLER1.marker.X", "ENABLED")
                .withValue("logging.handler.HANDLER2.marker.Y", "DISABLED")
                .getOrCreateConfig();

        final HandlerLoggingLevelConfig config1 = HandlerLoggingLevelConfig.create(configuration, "HANDLER1");
        final HandlerLoggingLevelConfig config2 = HandlerLoggingLevelConfig.create(configuration, "HANDLER2");

        // then
        assertThat(config1.isEnabled("com.example.Class", Level.INFO, markerX)).isTrue();
        assertThat(config1.isEnabled("com.example.Class", Level.ERROR, markerY)).isTrue();
        assertThat(config2.isEnabled("com.example.Class", Level.DEBUG, markerY)).isFalse();
        assertThat(config2.isEnabled("com.example.Class", Level.TRACE, markerX)).isFalse();
    }

    @Test
    void logLevelWithInheritedHandler() {
        // given
        final Marker markerM = new Marker("M");

        final Configuration configuration = createDefaultBuilder()
                .withValue("logging.level", "OFF")
                .withValue("logging.handler.HANDLER1.marker.M", "ENABLED")
                .withValue("logging.handler.HANDLER2.marker.M", "UNDEFINED")
                .getOrCreateConfig();

        final HandlerLoggingLevelConfig config1 = HandlerLoggingLevelConfig.create(configuration, "HANDLER1");
        final HandlerLoggingLevelConfig config2 = HandlerLoggingLevelConfig.create(configuration, "HANDLER2");

        // then
        assertThat(config1.isEnabled("com.example.Class", Level.INFO, markerM)).isTrue();
        assertThat(config1.isEnabled("com.example.Class", Level.TRACE, markerM)).isTrue();
        assertThat(config2.isEnabled("com.example.Class", Level.ERROR, markerM)).isFalse();
        assertThat(config2.isEnabled("com.example.Class", Level.DEBUG, markerM)).isFalse();
    }

    @Test
    void logLevelMarkerOverwrite() {
        // given
        final Marker markerA = new Marker("A");

        final Configuration configuration = createDefaultBuilder()
                .withValue("logging.marker.A", "DISABLED")
                .withValue("logging.handler.HANDLER1.marker.A", "ENABLED")
                .getOrCreateConfig();

        final HandlerLoggingLevelConfig config = HandlerLoggingLevelConfig.create(configuration, "HANDLER1");

        // then
        assertThat(config.isEnabled("com.example.ClassA", Level.INFO, markerA)).isTrue();
    }

    @Test
    void logLevelNoInheritanceForLevels() {
        // given
        final Marker markerBaz = new Marker("baz");

        final Configuration configuration = createDefaultBuilder()
                // Level
                .withValue("logging.level", "ERROR")
                .withValue("logging.level.com.foo", "TRACE")
                .withValue("logging.level.com.bar", "TRACE")
                // Markers
                .withValue("logging.marker.baz", "DISABLED")
                // Handler Markers
                .withValue("logging.handler.HANDLER1.level", "OFF")
                .withValue("logging.handler.HANDLER1.inheritLevels", "FALSE")
                .withValue("logging.handler.HANDLER1.marker.baz", "ENABLED")
                .getOrCreateConfig();
        final HandlerLoggingLevelConfig config = HandlerLoggingLevelConfig.create(configuration, "HANDLER1");

        // then
        assertThat(config.isEnabled("com.bar.some.Class", Level.INFO, markerBaz))
                .isTrue();
        assertThat(config.isEnabled("com.foo.some.Class", Level.TRACE)).isFalse();
        assertThat(config.isEnabled("com.foo.some.Class", Level.ERROR)).isFalse();
    }

    @Test
    void logLevelNoInheritanceForLevelsDefaultLevel() {
        // given
        final Marker markerBaz = new Marker("baz");

        final Configuration configuration = createDefaultBuilder()
                // Level
                .withValue("logging.level", "ERROR")
                .withValue("logging.level.com.foo", "TRACE")
                .withValue("logging.level.com.bar", "TRACE")
                // Markers
                .withValue("logging.marker.baz", "DISABLED")
                // Handler Markers
                .withValue("logging.handler.HANDLER1.inheritLevels", "FALSE")
                .withValue("logging.handler.HANDLER1.marker.baz", "ENABLED")
                .getOrCreateConfig();
        final HandlerLoggingLevelConfig config = HandlerLoggingLevelConfig.create(configuration, "HANDLER1");

        // then
        assertThat(config.isEnabled("com.bar.some.Class", Level.INFO, markerBaz))
                .isTrue();
        assertThat(config.isEnabled("com.foo.some.Class", Level.TRACE)).isFalse();
        assertThat(config.isEnabled("com.foo.some.Class", Level.INFO)).isTrue();
    }

    private TestConfigBuilder createDefaultBuilder() {
        return new TestConfigBuilder()
                .withConverter(MarkerState.class, new MarkerStateConverter())
                .withConverter(ConfigLevel.class, new ConfigLevelConverter());
    }
}
