// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.NestedConfig;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.ConfigViolationException;
import com.swirlds.config.api.validation.annotation.ConstraintMethod;
import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.config.api.validation.annotation.Min;
import com.swirlds.config.api.validation.annotation.Positive;
import com.swirlds.config.extensions.export.ConfigExport;
import com.swirlds.config.extensions.sources.PropertyFileConfigSource;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.config.impl.validators.DefaultConfigViolation;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ConfigApiRecordsTests {

    @Test
    void getConfigProxy() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("network.port", 8080))
                .withConfigDataType(NetworkConfig.class)
                .build();

        // when
        final NetworkConfig networkConfig = configuration.getConfigData(NetworkConfig.class);

        // then
        assertEquals(8080, networkConfig.port(), "Config data objects should be configured correctly");
    }

    @Test
    void getNotRegisteredDataObject() {
        // given
        final ConfigurationBuilder configurationBuilder =
                ConfigurationBuilder.create().withConfigDataType(NetworkConfig.class);

        // then
        assertThrows(
                IllegalStateException.class,
                configurationBuilder::build,
                "It should not be possible to create a config data object with undefined values");
    }

    @Test
    void getConfigProxyUndefinedValue() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // then
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration.getConfigData(NetworkConfig.class),
                "It should not be possible to create an object of a not registered config data type");
    }

    @Test
    void getConfigProxyDefaultValue() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("network.port", "8080"))
                .withConfigDataType(NetworkConfig.class)
                .build();

        // when
        final NetworkConfig networkConfig = configuration.getConfigData(NetworkConfig.class);

        // then
        assertEquals("localhost", networkConfig.server(), "Default values of config data objects should be used");
    }

    @Test
    void getConfigProxyDefaultValuesList() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("network.port", "8080"))
                .withConfigDataType(NetworkConfig.class)
                .build();

        // when
        final NetworkConfig networkConfig = configuration.getConfigData(NetworkConfig.class);
        final List<Integer> errorCodes = networkConfig.errorCodes();
        final Set<Long> errorCodeSet = networkConfig.errorCodeSet();

        // then
        assertNotNull(errorCodes, "Default values of config data objects should be used");
        assertEquals(2, errorCodes.size(), "List values should be supported for default values in config data objects");
        assertTrue(
                errorCodes.contains(404), "List values should be supported for default values in config data objects");
        assertTrue(
                errorCodes.contains(500), "List values should be supported for default values in config data objects");
        assertEquals(
                2, errorCodeSet.size(), "Set values should be supported for default values in config data objects");
        assertTrue(
                errorCodeSet.contains(404L),
                "Set values should be supported for default values in config data objects");
        assertTrue(
                errorCodeSet.contains(500L),
                "Set values should be supported for default values in config data objects");
    }

    @Test
    void getConfigProxyValuesList() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("network.port", "8080"))
                .withSource(new SimpleConfigSource("network.errorCodes", "1,2,3"))
                .withConfigDataType(NetworkConfig.class)
                .build();

        // when
        final NetworkConfig networkConfig = configuration.getConfigData(NetworkConfig.class);
        final List<Integer> errorCodes = networkConfig.errorCodes();
        final Set<Long> errorCodeSet = networkConfig.errorCodeSet();

        // then
        assertNotNull(errorCodes, "List values should be supported in config data objects");
        assertEquals(3, errorCodes.size(), "List values should be supported in config data objects");
        assertTrue(errorCodes.contains(1), "List values should be supported in config data objects");
        assertTrue(errorCodes.contains(2), "List values should be supported in config data objects");
        assertTrue(errorCodes.contains(3), "List values should be supported in config data objects");
        assertEquals(3, errorCodeSet.size(), "Set values should be supported in config data objects");
        assertTrue(errorCodeSet.contains(1L), "Set values should be supported in config data objects");
        assertTrue(errorCodeSet.contains(2L), "Set values should be supported in config data objects");
        assertTrue(errorCodeSet.contains(3L), "Set values should be supported in config data objects");
    }

    @Test
    void invalidDataRecordWillFailInit() {
        // given
        final ConfigurationBuilder configurationBuilder =
                ConfigurationBuilder.create().withConfigDataType(NetworkConfig.class);

        // then
        assertThrows(
                IllegalStateException.class,
                configurationBuilder::build,
                "values must be defined for all properties that are defined by registered config data types");
    }

    @Test
    void getConfigProxyOverwrittenDefaultValue() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("network.port", "8080"))
                .withSource(new SimpleConfigSource("network.server", "example.net"))
                .withConfigDataType(NetworkConfig.class)
                .build();

        // when
        final NetworkConfig networkConfig = configuration.getConfigData(NetworkConfig.class);

        // then
        assertEquals(
                "example.net",
                networkConfig.server(),
                "It must be possible to overwrite default values in object data types");
    }

    @Test
    void testMinConstrainAnnotation() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("network.port", "-1"))
                .withConfigDataType(NetworkConfig.class);

        // when
        final ConfigViolationException exception = assertThrows(
                ConfigViolationException.class,
                configurationBuilder::build,
                "Check for @Min annotation in NetworkConfig should end in violation");

        // then
        assertEquals(1, exception.getViolations().size());
        assertTrue(exception.getViolations().getFirst().propertyExists());
        assertEquals("network.port", exception.getViolations().getFirst().getPropertyName());
        assertEquals("-1", exception.getViolations().getFirst().getPropertyValue());
        assertEquals("Value must be >= 1", exception.getViolations().getFirst().getMessage());
    }

    @Test
    void testConstrainAnnotation() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("network.port", "8080"))
                .withSources(new SimpleConfigSource("network.server", "invalid"))
                .withConfigDataType(NetworkConfig.class);

        // when
        final ConfigViolationException exception = assertThrows(
                ConfigViolationException.class,
                configurationBuilder::build,
                "Check for @Constraint annotation in NetworkConfig should end in violation");

        // then
        assertEquals(1, exception.getViolations().size());
        assertTrue(exception.getViolations().getFirst().propertyExists());
        assertEquals("network.server", exception.getViolations().getFirst().getPropertyName());
        assertEquals("invalid", exception.getViolations().getFirst().getPropertyValue());
        assertEquals(
                "server must not be invalid",
                exception.getViolations().getFirst().getMessage());
    }

    @Test
    void testMultipleConstrainAnnotationsFail() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("network.port", "-1"))
                .withSources(new SimpleConfigSource("network.server", "invalid"))
                .withConfigDataType(NetworkConfig.class);

        // when
        final ConfigViolationException exception = assertThrows(
                ConfigViolationException.class,
                configurationBuilder::build,
                "Check for @Constraint annotation in NetworkConfig should end in violation");

        // then
        assertEquals(2, exception.getViolations().size());
    }

    @Test
    void testNullDefaultsInConfigDataRecord() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withConfigDataType(NullConfig.class)
                .build();

        // when
        final String value = configuration.getConfigData(NullConfig.class).value();
        final List<Integer> list = configuration.getConfigData(NullConfig.class).list();
        final Set<Integer> set = configuration.getConfigData(NullConfig.class).set();

        // then
        assertNull(value);
        assertNull(list);
        assertNull(set);
    }

    @Test
    void testEmptyCollectionsInConfigDataRecord() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withConfigDataType(EmptyCollectionConfig.class)
                .build();

        // when
        final List<Integer> list =
                configuration.getConfigData(EmptyCollectionConfig.class).list();
        final Set<Integer> set =
                configuration.getConfigData(EmptyCollectionConfig.class).set();

        // then
        assertIterableEquals(List.of(), list);
        assertIterableEquals(Set.of(), set);
    }

    @Test
    void testConfigRecordsSharingSameFileProperties() throws IOException, URISyntaxException {
        // given
        final Path configFile =
                Paths.get(ConfigApiTests.class.getResource("test.properties").toURI());

        final Configuration configuration = ConfigurationBuilder.create()
                .withConfigDataType(AppConfigFull.class)
                .withConfigDataType(AppConfigPartial.class)
                .withSource(new PropertyFileConfigSource(configFile))
                .build();

        AppConfigFull appConfigFull = configuration.getConfigData(AppConfigFull.class);
        assertEquals("ConfigTest", appConfigFull.name());
        assertEquals("1.0.0", appConfigFull.version());

        AppConfigPartial appConfigPartial = configuration.getConfigData(AppConfigPartial.class);
        assertEquals("ConfigTest", appConfigPartial.name());
    }

    @ConfigData("app")
    public record AppConfigFull(String name, String version) {}

    @ConfigData("app")
    public record AppConfigPartial(String name) {}

    @Nested
    class NestedRecordDirectSelfReference {

        @Test
        void test() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(Root.class);

            verifyCircularReferenceException(builder);
        }

        @ConfigData("circular")
        public record Root(SelfReferencing nested) {}

        @NestedConfig
        public record SelfReferencing(SelfReferencing circular) {}
    }

    @Nested
    class ConfigDataRecordUsedAsANestedComponent {

        /**
         * A config data record is registered and brings its own prefix, so it can not double as a group of properties
         * below another record. That makes a cycle back to the root structurally impossible, and it is reported as the
         * missing {@link NestedConfig} it really is rather than as a circular reference.
         */
        @Test
        void test() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(Root.class);

            verifyBuildFails(builder, "is neither annotated with NestedConfig", "nor has a converter registered");
        }

        @ConfigData("circular")
        public record Root(Nested nested) {}

        @NestedConfig
        public record Nested(Root circular) {}
    }

    @Nested
    class NestedRecordCircularReference {

        @Test
        void test() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(Root.class);

            verifyCircularReferenceException(builder);
        }

        @ConfigData("circular")
        public record Root(Nested1 nested1) {}

        @NestedConfig
        public record Nested1(Nested2 nested2) {}

        @NestedConfig
        public record Nested2(Nested1 circular) {}
    }

    @Nested
    class MultipleNestedSameRecords {

        @Test
        void test() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withValue("nested.left.value", "LeftValue")
                    .withValue("nested.right.value", "RightValue")
                    .withConfigDataType(Root.class)
                    .build();

            Root root = configuration.getConfigData(Root.class);
            assertNotNull(root);
            assertEquals("LeftValue", root.left().value());
            assertEquals("RightValue", root.right().value());
        }

        @ConfigData("nested")
        public record Root(Leaf left, Leaf right) {}

        @NestedConfig
        public record Leaf(String value) {}
    }

    @Nested
    class CustomConverterAppliedToNestedRecord {

        @Test
        void test() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withValue("root.nested", "1:2")
                    // next properties should be ignored due to converter
                    .withValue("root.nested.left", "left")
                    .withValue("root.nested.right", "right")
                    .withConfigDataType(Root.class)
                    .withConverter(Nested.class, value -> {
                        String[] parts = value.split(":");
                        return new Nested(parts[0], parts[1]);
                    })
                    .build();

            Root root = configuration.getConfigData(Root.class);
            assertNotNull(root);
            assertEquals("1", root.nested().left());
            assertEquals("2", root.nested().right());
        }

        @ConfigData("root")
        public record Root(Nested nested) {}

        public record Nested(String left, String right) {}
    }

    @Nested
    class DifferentPropertyTypesNestedRecord {

        @Test
        void test() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withValue("root.nested.stringProperty", "val1")
                    .withValue("root.nested.boolProperty", "true")
                    .withValue("root.nested.intProperty", "42")
                    .withValue("root.nested.longProperty", "123456789")
                    .withValue("root.nested.doubleProperty", "3.14")
                    .withValue("root.nested.listProperty", "a,b,c")
                    .withValue("root.nested.setProperty", "x,y,z,x")
                    .withConfigDataType(Root.class)
                    .build();

            Root root = configuration.getConfigData(Root.class);
            assertNotNull(root);
            assertEquals("val1", root.nested().stringProperty());
            assertTrue(root.nested().boolProperty());
            assertEquals(42, root.nested().intProperty());
            assertEquals(123456789L, root.nested().longProperty());
            assertEquals(3.14, root.nested().doubleProperty());
            assertEquals(List.of("a", "b", "c"), root.nested().listProperty());
            assertEquals(Set.of("x", "y", "z"), root.nested().setProperty());
        }

        @ConfigData("root")
        public record Root(Nested nested) {}

        @NestedConfig
        public record Nested(
                String stringProperty,
                boolean boolProperty,
                int intProperty,
                long longProperty,
                double doubleProperty,
                List<String> listProperty,
                Set<String> setProperty) {}
    }

    @Nested
    class NestedRecordDefaultValuesAndCustomNames {

        @Test
        void test() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withValue("root.nested.customName", "custom")
                    .withConfigDataType(Root.class)
                    .build();

            Root root = configuration.getConfigData(Root.class);
            assertNotNull(root);
            assertEquals("custom", root.nested().property());
            assertEquals("val1", root.nested().stringProperty());
            assertTrue(root.nested().boolProperty());
            assertEquals(42, root.nested().intProperty());
            assertEquals(123456789L, root.nested().longProperty());
            assertEquals(3.14, root.nested().doubleProperty());
            assertEquals(List.of("a", "b", "c"), root.nested().listProperty());
            assertEquals(Set.of("x", "y", "z"), root.nested().setProperty());
        }

        @ConfigData("root")
        public record Root(Nested nested) {}

        @NestedConfig
        public record Nested(
                @ConfigProperty(value = "customName") String property,
                @ConfigProperty(defaultValue = "val1") String stringProperty,
                @ConfigProperty(defaultValue = "true") boolean boolProperty,
                @ConfigProperty(defaultValue = "42") int intProperty,
                @ConfigProperty(defaultValue = "123456789") long longProperty,
                @ConfigProperty(defaultValue = "3.14") double doubleProperty,
                @ConfigProperty(defaultValue = "a,b,c") List<String> listProperty,
                @ConfigProperty(defaultValue = "x,y,z") Set<String> setProperty) {}
    }

    @Nested
    class NestedRecordWithConverter {

        @Test
        void test() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withValue("root.nested.pair", "1:2")
                    .withConfigDataType(Root.class)
                    .withConverter(Pair.class, value -> {
                        String[] parts = value.split(":");
                        return new Pair(parts[0], parts[1]);
                    })
                    .build();

            Root root = configuration.getConfigData(Root.class);
            assertNotNull(root);
            assertEquals("1", root.nested().pair().getLeft());
            assertEquals("2", root.nested().pair().getRight());
        }

        @ConfigData("root")
        public record Root(Nested nested) {}

        @NestedConfig
        public record Nested(Pair pair) {}

        public static class Pair {
            private final String left;
            private final String right;

            public Pair(String left, String right) {
                this.left = left;
                this.right = right;
            }

            public String getLeft() {
                return left;
            }

            public String getRight() {
                return right;
            }
        }
    }

    @Nested
    class NestedPropertyIsValidatedWithConstraintAnnotation {

        @Test
        void testNoViolation() {
            ConfigurationBuilder builder = ConfigurationBuilder.create()
                    .withValue("root.nested.intProperty", "10")
                    .withConfigDataType(Root.class);

            assertDoesNotThrow(builder::build, "No violation should happen");
        }

        @Test
        void testViolation() {
            ConfigurationBuilder builder = ConfigurationBuilder.create()
                    .withValue("root.nested.intProperty", "-10")
                    .withConfigDataType(Root.class);

            ConfigViolation violation = verifySingleViolation(builder);
            assertEquals("root.nested.intProperty", violation.getPropertyName());
            assertEquals("-10", violation.getPropertyValue());
            assertEquals("Value must be > 0", violation.getMessage());
        }

        @ConfigData("root")
        public record Root(Nested nested) {}

        @NestedConfig
        public record Nested(@Positive int intProperty) {}
    }

    @Nested
    class SameNestedRecordValidatedIndependently {

        @Test
        void testOnlyViolatingOccurrenceIsReported() {
            ConfigurationBuilder builder = ConfigurationBuilder.create()
                    .withValue("root.left.value", "-1")
                    .withValue("root.right.value", "5")
                    .withConfigDataType(Root.class);

            ConfigViolation violation = verifySingleViolation(builder);
            assertEquals("root.left.value", violation.getPropertyName());
            assertEquals("-1", violation.getPropertyValue());
        }

        @Test
        void testBothOccurrencesAreReportedSeparately() {
            ConfigurationBuilder builder = ConfigurationBuilder.create()
                    .withValue("root.left.value", "-1")
                    .withValue("root.right.value", "-2")
                    .withConfigDataType(Root.class);

            List<ConfigViolation> violations = verifyViolations(builder, 2);
            assertIterableEquals(
                    List.of("root.left.value", "root.right.value"),
                    violations.stream()
                            .map(ConfigViolation::getPropertyName)
                            .sorted()
                            .toList());
            assertIterableEquals(
                    List.of("-1", "-2"),
                    violations.stream()
                            .map(ConfigViolation::getPropertyValue)
                            .sorted()
                            .toList());
        }

        @ConfigData("root")
        public record Root(Leaf left, Leaf right) {}

        @NestedConfig
        public record Leaf(@Positive int value) {}
    }

    @Nested
    class DeeplyNestedPropertyValidation {

        @Test
        void testNoViolation() {
            ConfigurationBuilder builder = ConfigurationBuilder.create()
                    .withValue("root.level1.level2.value", "5")
                    .withConfigDataType(Root.class);

            assertDoesNotThrow(builder::build, "No violation should happen");
        }

        @Test
        void testMinViolation() {
            ConfigurationBuilder builder = ConfigurationBuilder.create()
                    .withValue("root.level1.level2.value", "0")
                    .withConfigDataType(Root.class);

            ConfigViolation violation = verifySingleViolation(builder);
            assertEquals("root.level1.level2.value", violation.getPropertyName());
            assertEquals("Value must be >= 1", violation.getMessage());
        }

        @Test
        void testMaxViolation() {
            ConfigurationBuilder builder = ConfigurationBuilder.create()
                    .withValue("root.level1.level2.value", "11")
                    .withConfigDataType(Root.class);

            ConfigViolation violation = verifySingleViolation(builder);
            assertEquals("root.level1.level2.value", violation.getPropertyName());
            assertEquals("Value must be <= 10", violation.getMessage());
        }

        @ConfigData("root")
        public record Root(Level1 level1) {}

        @NestedConfig
        public record Level1(Level2 level2) {}

        @NestedConfig
        public record Level2(@Min(1) @Max(10) int value) {}
    }

    @Nested
    class NestedPropertyNameRespectsConfigPropertyAnnotation {

        @Test
        void test() {
            ConfigurationBuilder builder = ConfigurationBuilder.create()
                    .withValue("root.customNested.customValue", "-1")
                    .withConfigDataType(Root.class);

            ConfigViolation violation = verifySingleViolation(builder);
            assertEquals("root.customNested.customValue", violation.getPropertyName());
        }

        @ConfigData("root")
        public record Root(
                @ConfigProperty(value = "customNested") Leaf nested) {}

        @NestedConfig
        public record Leaf(
                @ConfigProperty(value = "customValue") @Positive
                int value) {}
    }

    @Nested
    class ConstraintMethodOnNestedRecord {

        @Test
        void testNoViolation() {
            ConfigurationBuilder builder = ConfigurationBuilder.create()
                    .withValue("root.left.value", "1")
                    .withValue("root.right.value", "2")
                    .withConfigDataType(Root.class);

            assertDoesNotThrow(builder::build, "No violation should happen");
        }

        @Test
        void testMethodIsInvokedOnEachNestedInstance() {
            ConfigurationBuilder builder = ConfigurationBuilder.create()
                    .withValue("root.left.value", "-1")
                    .withValue("root.right.value", "-2")
                    .withConfigDataType(Root.class);

            List<ConfigViolation> violations = verifyViolations(builder, 2);
            assertIterableEquals(
                    List.of("-1", "-2"),
                    violations.stream()
                            .map(ConfigViolation::getPropertyValue)
                            .sorted()
                            .toList());
        }

        @ConfigData("root")
        public record Root(Leaf left, Leaf right) {}

        @NestedConfig
        public record Leaf(@ConstraintMethod("check") int value) {

            public ConfigViolation check(final Configuration configuration) {
                if (value > 0) {
                    return null;
                }
                return new DefaultConfigViolation("root.check", value + "", true, "must be positive");
            }
        }
    }

    @Nested
    class ConstraintMethodOnComponentHoldingNestedRecord {

        /**
         * A component that holds a nested config data object is not a property that can be set, but a constraint can
         * still be defined for the group as a whole, so the validation has to see the component itself.
         */
        @Test
        void test() {
            ConfigurationBuilder builder = ConfigurationBuilder.create()
                    .withValue("root.leaf.min", "10")
                    .withValue("root.leaf.max", "5")
                    .withConfigDataType(Root.class);

            ConfigViolation violation = verifySingleViolation(builder);
            assertEquals("root.leaf", violation.getPropertyName());
            assertEquals("min must not be greater than max", violation.getMessage());
        }

        @Test
        void testNoViolation() {
            ConfigurationBuilder builder = ConfigurationBuilder.create()
                    .withValue("root.leaf.min", "1")
                    .withValue("root.leaf.max", "5")
                    .withConfigDataType(Root.class);

            assertDoesNotThrow(builder::build, "No violation should happen");
        }

        @ConfigData("root")
        public record Root(@ConstraintMethod("checkRange") Leaf leaf) {

            public ConfigViolation checkRange(final Configuration configuration) {
                if (leaf.min() <= leaf.max()) {
                    return null;
                }
                return new DefaultConfigViolation("root.leaf", leaf + "", true, "min must not be greater than max");
            }
        }

        @NestedConfig
        public record Leaf(int min, int max) {}
    }

    @Nested
    class FlatteningEquivalence {

        /**
         * Grouping is all that {@link NestedConfig} does: a nested config data object has to behave exactly as if its
         * properties had been declared on the enclosing record with dotted names and components of their own. These
         * tests build both shapes from the same config source and compare what an application can observe.
         */
        @Test
        void testTheSameValuesAreRead() {
            Configuration nested =
                    definedCount().withConfigDataType(NestedRoot.class).build();
            Configuration flat =
                    definedCount().withConfigDataType(FlatRoot.class).build();

            NestedRoot nestedRoot = nested.getConfigData(NestedRoot.class);
            FlatRoot flatRoot = flat.getConfigData(FlatRoot.class);

            assertEquals(flatRoot.leafValue(), nestedRoot.leaf().value());
            assertEquals(flatRoot.leafCount(), nestedRoot.leaf().count());
        }

        @Test
        void testTheSameDefaultsAreUsed() {
            Configuration nested = ConfigurationBuilder.create()
                    .withValue("root.leaf.count", "7")
                    .withConfigDataType(NestedRoot.class)
                    .build();
            Configuration flat = ConfigurationBuilder.create()
                    .withValue("root.leaf.count", "7")
                    .withConfigDataType(FlatRoot.class)
                    .build();

            assertEquals(
                    "fromRecord", nested.getConfigData(NestedRoot.class).leaf().value());
            assertEquals(
                    flat.getConfigData(FlatRoot.class).leafValue(),
                    nested.getConfigData(NestedRoot.class).leaf().value());
        }

        @Test
        void testTheSameConfigurationIsExported() {
            Configuration nested =
                    definedCount().withConfigDataType(NestedRoot.class).build();
            Configuration flat =
                    definedCount().withConfigDataType(FlatRoot.class).build();

            assertEquals(exportOf(flat), exportOf(nested));
        }

        /**
         * A property of a group that resolves to no value fails the creation of the configuration, exactly as the same
         * property declared flat does. The group itself is never what a config source defines.
         */
        @Test
        void testTheSameFailureWhenAPropertyHasNoValue() {
            assertThrows(IllegalStateException.class, () -> ConfigurationBuilder.create()
                    .withConfigDataType(FlatRoot.class)
                    .build());
            assertThrows(IllegalStateException.class, () -> ConfigurationBuilder.create()
                    .withConfigDataType(NestedRoot.class)
                    .build());
        }

        /**
         * A constraint on a property of a group is reported under the full property name, which is the name the same
         * property declared flat is reported under.
         */
        @Test
        void testTheSameConstraintViolationIsReported() {
            ConfigViolation nestedViolation = verifySingleViolation(ConfigurationBuilder.create()
                    .withValue("root.leaf.count", "0")
                    .withConfigDataType(NestedRoot.class));
            ConfigViolation flatViolation = verifySingleViolation(ConfigurationBuilder.create()
                    .withValue("root.leaf.count", "0")
                    .withConfigDataType(FlatRoot.class));

            assertEquals("root.leaf.count", nestedViolation.getPropertyName());
            assertEquals(flatViolation.getPropertyName(), nestedViolation.getPropertyName());
            assertEquals(flatViolation.getPropertyValue(), nestedViolation.getPropertyValue());
        }

        private static ConfigurationBuilder definedCount() {
            return ConfigurationBuilder.create()
                    .withValue("root.leaf.value", "fromSource")
                    .withValue("root.leaf.count", "7");
        }

        private static String exportOf(final Configuration configuration) {
            StringBuilder builder = new StringBuilder();
            ConfigExport.addConfigContents(configuration, builder);
            return builder.toString();
        }

        @ConfigData("root")
        public record NestedRoot(Leaf leaf) {}

        @NestedConfig
        public record Leaf(
                @ConfigProperty(defaultValue = "fromRecord") String value,
                @Min(1) int count) {}

        @ConfigData("root")
        public record FlatRoot(
                @ConfigProperty(value = "leaf.value", defaultValue = "fromRecord")
                String leafValue,

                @ConfigProperty(value = "leaf.count") @Min(1)
                int leafCount) {}
    }

    @Nested
    class ConverterBackedRecordIsNotWalkedForConstraints {

        /**
         * A record without {@link NestedConfig} is a single property whose value a converter creates, so it is not
         * walked into. A constraint annotation on one of its components describes a component of that value rather
         * than a property of the configuration and is not enforced, exactly as before nested config data objects
         * existed.
         */
        @Test
        void test() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withValue("root.pair", "-1:2")
                    .withConfigDataType(Root.class)
                    .withConverter(Pair.class, ConverterBackedRecordIsNotWalkedForConstraints::convert)
                    .build();

            Root root = configuration.getConfigData(Root.class);
            assertEquals(-1, root.pair().left());
            assertEquals(2, root.pair().right());
        }

        private static Pair convert(final String value) {
            String[] parts = value.split(":");
            return new Pair(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }

        @ConfigData("root")
        public record Root(Pair pair) {}

        public record Pair(@Positive int left, int right) {}
    }

    @Nested
    class CollectionOfNestedRecords {

        @Test
        void testListOfNestedRecordsIsRejected() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(ListRoot.class);

            verifyBuildFails(builder, "root.leaves", NestedConfig.class.getSimpleName(), "element of a collection");
        }

        @Test
        void testSetOfNestedRecordsIsRejected() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(SetRoot.class);

            verifyBuildFails(builder, "root.leaves", NestedConfig.class.getSimpleName(), "element of a collection");
        }

        /**
         * Only a collection of a nested config data object is rejected. A collection of a type that a converter creates
         * stays a single property that is read as a list of values.
         */
        @Test
        void testCollectionOfAConvertedTypeStillWorks() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withValue("root.values", "a,b")
                    .withConfigDataType(ConvertedListRoot.class)
                    .build();

            assertEquals(
                    List.of("a", "b"),
                    configuration.getConfigData(ConvertedListRoot.class).values());
        }

        @ConfigData("root")
        public record ListRoot(List<Leaf> leaves) {}

        @ConfigData("root")
        public record SetRoot(Set<Leaf> leaves) {}

        @ConfigData("root")
        public record ConvertedListRoot(List<String> values) {}

        @NestedConfig
        public record Leaf(
                @ConfigProperty(defaultValue = "fromRecord") String value) {}
    }

    @Nested
    class NestedConfigIsNotAConfigDataType {

        /**
         * A nested config data object is a group of properties that takes its prefix from the component that holds it,
         * so registering it on its own has no meaningful property names and has to be rejected.
         */
        @Test
        void testRegisteringANestedConfigIsRejected() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(NestedOnly.class);

            verifyBuildFails(builder, NestedConfig.class.getSimpleName(), "never registered on its own");
        }

        @Test
        void testRecordWithBothAnnotationsIsRejected() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(BothAnnotations.class);

            verifyBuildFails(builder, NestedConfig.class.getSimpleName(), "never registered on its own");
        }

        /**
         * The two annotations describe the two different roles a config record can have, so a record carrying both is a
         * mistake wherever it turns up. Being rejected only on the registration path would let it through as a
         * component, where its prefix is silently ignored.
         */
        @Test
        void testRecordWithBothAnnotationsIsRejectedAsANestedComponent() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(BothAnnotationsRoot.class);

            verifyBuildFails(builder, "annotated with both ConfigData and NestedConfig", "mutually exclusive");
        }

        /**
         * A nested config data object is read property by property, so a converter for it would never be used. Leaving
         * a converter registered while moving a type over to {@link NestedConfig} has to be an error rather than a
         * silent change of behaviour.
         */
        @Test
        void testNestedConfigWithARegisteredConverterIsRejected() {
            ConfigurationBuilder builder = ConfigurationBuilder.create()
                    .withConfigDataType(Root.class)
                    .withConverter(NestedOnly.class, _ -> new NestedOnly("converted"));

            verifyBuildFails(builder, "also has a converter", "Remove one of the two");
        }

        @NestedConfig
        public record NestedOnly(String value) {}

        @ConfigData("both")
        @NestedConfig
        public record BothAnnotations(String value) {}

        @ConfigData("root")
        public record Root(NestedOnly nested) {}

        @ConfigData("root")
        public record BothAnnotationsRoot(BothAnnotations nested) {}
    }

    @Nested
    class InvalidNestedRecordDeclarations {

        /**
         * A group has no value of its own that a config source could define, so there is nothing a default value of the
         * component holding it could mean.
         */
        @Test
        void testDefaultValueOnAComponentHoldingAGroupIsRejected() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(DefaultValueRoot.class);

            verifyBuildFails(builder, "root.leaf", "nested config data object", "group of properties rather than");
        }

        /**
         * The null marker is a default value like any other here. A group is always created, and a component that
         * should be able to be absent is a property of the group rather than the group itself.
         */
        @Test
        void testNullDefaultValueOnAComponentHoldingAGroupIsRejected() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(NullDefaultRoot.class);

            verifyBuildFails(builder, "root.leaf", "nested config data object", "group of properties rather than");
        }

        /**
         * The properties of a group follow from its type, and the annotation processor reads the declared type while
         * the runtime reads the erasure. Requiring the record type to be named is what keeps the two the same.
         */
        @Test
        void testTypeVariableComponentIsRejected() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(TypeVariableRoot.class);

            verifyBuildFails(builder, "root.leaf", "instead of naming the record type");
        }

        @Test
        void testGenericComponentIsRejected() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(GenericRoot.class);

            verifyBuildFails(builder, "root.leaf", "instead of naming the record type");
        }

        /**
         * A record valued component is either a group or a value that a converter creates from a single property.
         * Being neither is a forgotten annotation, which is reported instead of silently producing a property that can
         * not be set.
         */
        @Test
        void testRecordComponentThatIsNeitherNestedNorConvertedIsRejected() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(PlainRecordRoot.class);

            verifyBuildFails(builder, "root.leaf", "neither annotated with", "nor has a converter registered");
        }

        @Test
        void testNestedRecordThatIsNotPublicIsRejected() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(NotPublicRoot.class);

            verifyBuildFails(builder, "it is not public");
        }

        @ConfigData("root")
        public record DefaultValueRoot(
                @ConfigProperty(defaultValue = "nonsense") Leaf leaf) {}

        @ConfigData("root")
        public record NullDefaultRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                Leaf leaf) {}

        @ConfigData("root")
        public record TypeVariableRoot<T extends Leaf>(T leaf) {}

        @ConfigData("root")
        public record GenericRoot(GenericLeaf<String> leaf) {}

        @ConfigData("root")
        public record PlainRecordRoot(PlainRecord leaf) {}

        @ConfigData("root")
        public record NotPublicRoot(NotPublicLeaf leaf) {}

        @NestedConfig
        public record Leaf(
                @ConfigProperty(defaultValue = "fromRecord") String value) {}

        @NestedConfig
        public record GenericLeaf<T>(T value) {}

        public record PlainRecord(String value) {}

        @NestedConfig
        record NotPublicLeaf(
                @ConfigProperty(defaultValue = "fromRecord") String value) {}
    }

    private static void verifyBuildFails(final ConfigurationBuilder builder, final String... expectedMessageParts) {
        IllegalStateException exception = assertThrows(IllegalStateException.class, builder::build);
        Throwable cause = exception.getCause();
        assertInstanceOf(IllegalArgumentException.class, cause, "Expected cause for " + exception.getMessage());
        for (final String expectedMessagePart : expectedMessageParts) {
            assertTrue(
                    cause.getMessage().contains(expectedMessagePart),
                    "Expected message to contain '" + expectedMessagePart + "' but was '" + cause.getMessage() + "'");
        }
    }

    private static ConfigViolation verifySingleViolation(final ConfigurationBuilder builder) {
        return verifyViolations(builder, 1).getFirst();
    }

    private static List<ConfigViolation> verifyViolations(final ConfigurationBuilder builder, final int expectedCount) {
        ConfigViolationException exception =
                assertThrows(ConfigViolationException.class, builder::build, "Violation should happen");
        assertEquals(
                expectedCount,
                exception.getViolations().size(),
                "Unexpected violations: "
                        + exception.getViolations().stream()
                                .map(violation -> violation.getPropertyName() + "=" + violation.getPropertyValue())
                                .toList());
        return exception.getViolations();
    }

    private static void verifyCircularReferenceException(ConfigurationBuilder builder) {
        try {
            builder.build();
            fail("Expected IllegalStateException due to circular reference");
        } catch (IllegalStateException e) {
            assertInstanceOf(IllegalStateException.class, e.getCause(), "Expected cause for IllegalStateException");
            assertTrue(e.getCause().getMessage().contains("Circular reference detected"));
        }
    }
}
