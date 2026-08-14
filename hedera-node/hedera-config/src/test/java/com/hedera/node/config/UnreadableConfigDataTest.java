// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.config.unreadable.UnreadableConfig;
import com.hedera.node.config.unreadable.UnreadableConfig.ConstrainedConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.reflection.ConfigReflectionUtils;
import com.swirlds.config.extensions.reflection.ConfigReflectionUtils.ConfigDataProperty;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.lang.reflect.RecordComponent;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers what the config reflection does with a config data object whose value it can not read.
 * <p>
 * A record is created by {@code com.swirlds.config.impl} and read by {@code com.swirlds.config.extensions}, which are
 * two different modules, so a package that is exported to the first but not to the second is created normally while
 * nothing below it can be read. {@link #exportToTheConfigFactoryOnly()} sets up exactly that, since it can not be
 * expressed by the build: the export the fixture needs is qualified, while the build grants module wide ones.
 * <p>
 * Walking into a record is only possible by reading a value, so the properties below such a component used to be
 * dropped. What is dropped that way is not a value but the properties themselves, and the two callers need different
 * things from that: the property names follow from the record type and have to stay complete, while a constraint can
 * only be checked against a value and therefore has to fail rather than silently count as checked.
 */
final class UnreadableConfigDataTest {

    /**
     * Exports the package of the fixture to the module that creates a config data object, and to that module only. The
     * package is declared by this module, so this module is the one allowed to export it.
     */
    @BeforeAll
    static void exportToTheConfigFactoryOnly() {
        UnreadableConfigDataTest.class
                .getModule()
                .addExports(
                        UnreadableConfig.class.getPackageName(),
                        ModuleLayer.boot().findModule("com.swirlds.config.impl").orElseThrow());
    }

    @Test
    @DisplayName("the fixture is really unreadable, otherwise the rest of this class proves nothing")
    void packageIsNotExportedToTheReflection() {
        final RecordComponent leaf = UnreadableConfig.class.getRecordComponents()[0];

        assertThat(UnreadableConfig.class
                        .getModule()
                        .isExported(UnreadableConfig.class.getPackageName(), ConfigReflectionUtils.class.getModule()))
                .as("the package must not be exported to the module the reflection runs in")
                .isFalse();
        assertThat(leaf.getAccessor().canAccess(new UnreadableConfig(null)))
                .as("this test class is in the same module, so it can read what the reflection can not")
                .isTrue();
    }

    @Test
    @DisplayName("getAllProperties() reports every property below an unreadable record")
    void allPropertiesAreStillNamed() {
        final Configuration configuration = configuration();

        final List<String> names = ConfigReflectionUtils.getAllProperties(configuration)
                .map(ConfigDataProperty::propertyName)
                .toList();

        // the names follow from the record type alone, so nothing a config source can set may be missing. A caller
        // that collects the known property names would otherwise report these as not used by any config data type
        assertThat(names)
                .contains("unreadable.leaf.plain", "unreadable.leaf.count")
                .doesNotContain("unreadable.leaf");
    }

    @Test
    @DisplayName("reading the value of such a property fails")
    void readingTheValueFails() {
        final Configuration configuration = configuration();

        final ConfigDataProperty property = ConfigReflectionUtils.getAllProperties(configuration)
                .filter(candidate -> "unreadable.leaf.plain".equals(candidate.propertyName()))
                .findFirst()
                .orElseThrow();

        assertThatThrownBy(property::propertyValue)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unreadable.leaf.plain")
                .hasMessageContaining("does not export");
    }

    @Test
    @DisplayName("a caller that only logs the configuration skips the property instead of failing")
    void tolerantCallerSkipsTheProperty() {
        final Configuration configuration = configuration();

        assertThatCode(() -> Utils.allProperties(configuration)).doesNotThrowAnyException();
        assertThat(Utils.allProperties(configuration)).doesNotContainKey("unreadable.leaf.plain");
    }

    @Test
    @DisplayName("a constraint below an unreadable record is not silently left unchecked")
    void constraintBelowAnUnreadableRecordFails() {
        // Finding no violation and not being able to look are not the same answer, so the validation fails instead of
        // passing a check that never ran.
        assertThatThrownBy(() -> new TestConfigBuilder(false)
                        .withConfigDataType(ConstrainedConfig.class)
                        .getOrCreateConfig())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("constrained.leaf.constrained")
                .hasMessageContaining("does not export");
    }

    /**
     * Only a property that really carries a constraint is unresolvable. An unreadable record without one has nothing
     * to check, so it must not fail the configuration.
     */
    @Test
    @DisplayName("an unreadable record without a constraint still builds")
    void unconstrainedRecordStillBuilds() {
        assertThatCode(UnreadableConfigDataTest::configuration).doesNotThrowAnyException();
    }

    private static Configuration configuration() {
        return new TestConfigBuilder(false)
                .withConfigDataType(UnreadableConfig.class)
                .getOrCreateConfig();
    }
}
