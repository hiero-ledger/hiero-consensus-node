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
import com.swirlds.config.api.ConfigDefault;
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
import com.swirlds.config.extensions.sources.PropertyFileConfigSource;
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
                .withValue("network.port", "8080")
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
                .withValue("network.port", "8080")
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
                .withValue("network.port", "8080")
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
                .withValue("network.port", "8080")
                .withValue("network.errorCodes", "1,2,3")
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
                .withValue("network.port", "8080")
                .withValue("network.server", "example.net")
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
        final ConfigurationBuilder configurationBuilder =
                ConfigurationBuilder.create().withValue("network.port", "-1").withConfigDataType(NetworkConfig.class);

        // when
        final ConfigViolationException exception = assertThrows(
                ConfigViolationException.class,
                configurationBuilder::build,
                "Check for @Min annotation in NetworkConfig should end in violation");

        // then
        assertEquals(1, exception.getViolations().size());
        assertTrue(exception.getViolations().get(0).propertyExists());
        assertEquals("network.port", exception.getViolations().get(0).getPropertyName());
        assertEquals("-1", exception.getViolations().get(0).getPropertyValue());
        assertEquals("Value must be >= 1", exception.getViolations().get(0).getMessage());
    }

    @Test
    void testConstrainAnnotation() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withValue("network.port", "8080")
                .withValue("network.server", "invalid")
                .withConfigDataType(NetworkConfig.class);

        // when
        final ConfigViolationException exception = assertThrows(
                ConfigViolationException.class,
                configurationBuilder::build,
                "Check for @Constraint annotation in NetworkConfig should end in violation");

        // then
        assertEquals(1, exception.getViolations().size());
        assertTrue(exception.getViolations().get(0).propertyExists());
        assertEquals("network.server", exception.getViolations().get(0).getPropertyName());
        assertEquals("invalid", exception.getViolations().get(0).getPropertyValue());
        assertEquals(
                "server must not be invalid", exception.getViolations().get(0).getMessage());
    }

    @Test
    void testMultipleConstrainAnnotationsFail() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withValue("network.port", "-1")
                .withValue("network.server", "invalid")
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

    @Nested
    class SharedConfigRecordProperty {

        @Test
        void test() throws IOException, URISyntaxException {
            // given
            final Path configFile = Paths.get(
                    ConfigApiTests.class.getResource("test.properties").toURI());

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
    }

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

        public class Pair {
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
    class ConverterBackedNestedRecordIsStillValidated {

        /**
         * A converter decides how a record valued property is populated, but a constraint on one of its components is
         * about the resolved value and is therefore still enforced.
         */
        @Test
        void test() {
            ConfigurationBuilder builder = ConfigurationBuilder.create()
                    .withValue("root.pair", "-1:2")
                    .withConfigDataType(Root.class)
                    .withConverter(Pair.class, ConverterBackedNestedRecordIsStillValidated::convert);

            ConfigViolation violation = verifySingleViolation(builder);
            assertEquals("root.pair.left", violation.getPropertyName());
            assertEquals("-1", violation.getPropertyValue());
        }

        @Test
        void testNoViolation() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withValue("root.pair", "1:2")
                    .withConfigDataType(Root.class)
                    .withConverter(Pair.class, ConverterBackedNestedRecordIsStillValidated::convert)
                    .build();

            Root root = configuration.getConfigData(Root.class);
            assertEquals(1, root.pair().left());
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
    class NullNestedRecordIsNotTraversed {

        @Test
        void test() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withConfigDataType(Root.class)
                    .withConverter(Pair.class, _ -> new Pair(1))
                    .build();

            Root root = configuration.getConfigData(Root.class);
            assertNull(root.pair());
        }

        @ConfigData("root")
        public record Root(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                Pair pair) {}

        public record Pair(@Positive int left) {}
    }

    @Nested
    class NestedRecordDefaultsFromUsageSite {

        @Test
        void testDefaultIsUsedWhenNoValueIsDefined() {
            Configuration configuration =
                    ConfigurationBuilder.create().withConfigDataType(Root.class).build();

            Root root = configuration.getConfigData(Root.class);
            assertEquals("fromSite", root.leaf().value());
            assertEquals(42, root.leaf().count());
        }

        @Test
        void testDefinedValueBeatsDefault() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withValue("root.leaf.count", "7")
                    .withConfigDataType(Root.class)
                    .build();

            Root root = configuration.getConfigData(Root.class);
            assertEquals("fromSite", root.leaf().value());
            assertEquals(7, root.leaf().count());
        }

        @ConfigData("root")
        public record Root(
                @ConfigDefault(property = "value", defaultValue = "fromSite")
                @ConfigDefault(property = "count", defaultValue = "42")
                Leaf leaf) {}

        @NestedConfig
        public record Leaf(String value, int count) {}
    }

    @Nested
    class SameNestedRecordWithDifferentDefaultsPerSite {

        @Test
        void test() {
            Configuration configuration =
                    ConfigurationBuilder.create().withConfigDataType(Root.class).build();

            Root root = configuration.getConfigData(Root.class);
            assertEquals("leftDefault", root.left().value());
            assertEquals(1, root.left().count());
            assertEquals("rightDefault", root.right().value());
            assertEquals(2, root.right().count());
        }

        @ConfigData("root")
        public record Root(
                @ConfigDefault(property = "value", defaultValue = "leftDefault")
                @ConfigDefault(property = "count", defaultValue = "1")
                Leaf left,

                @ConfigDefault(property = "value", defaultValue = "rightDefault")
                @ConfigDefault(property = "count", defaultValue = "2")
                Leaf right) {}

        @NestedConfig
        public record Leaf(String value, int count) {}
    }

    @Nested
    class ConfigDefaultCallStyles {

        /**
         * A repeatable annotation is only visible through {@code getAnnotationsByType}, so all three ways of writing
         * the defaults have to resolve the same way.
         */
        @Test
        void test() {
            Configuration configuration =
                    ConfigurationBuilder.create().withConfigDataType(Root.class).build();

            Root root = configuration.getConfigData(Root.class);
            assertEquals("single", root.single().value());
            assertEquals("repeated", root.repeated().value());
            assertEquals(1, root.repeated().count());
            assertEquals("container", root.container().value());
            assertEquals(2, root.container().count());
        }

        @ConfigData("root")
        public record Root(
                @ConfigDefault(property = "value", defaultValue = "single")
                LeafWithDefault single,

                @ConfigDefault(property = "value", defaultValue = "repeated")
                @ConfigDefault(property = "count", defaultValue = "1")
                Leaf repeated,

                @ConfigDefault.List({
                    @ConfigDefault(property = "value", defaultValue = "container"),
                    @ConfigDefault(property = "count", defaultValue = "2")
                })
                Leaf container) {}

        @NestedConfig
        public record Leaf(String value, int count) {}

        @NestedConfig
        public record LeafWithDefault(
                String value,
                @ConfigProperty(defaultValue = "0") int count) {}
    }

    @Nested
    class NestedRecordDefaultsWithSeparatorInValue {

        /**
         * The property and the value are separate annotation members, so a value containing the separator of the old
         * {@code "property=value"} form needs no escaping.
         */
        @Test
        void test() {
            Configuration configuration =
                    ConfigurationBuilder.create().withConfigDataType(Root.class).build();

            assertEquals("a=b=c", configuration.getConfigData(Root.class).leaf().value());
        }

        @ConfigData("root")
        public record Root(
                @ConfigDefault(property = "value", defaultValue = "a=b=c")
                Leaf leaf) {}

        @NestedConfig
        public record Leaf(String value) {}
    }

    @Nested
    class NestedRecordDefaultsWithDottedPath {

        /**
         * An entry of an enclosing config data object wins over an entry that is declared closer to the property, so a
         * usage site can override the defaults of everything below it.
         */
        @Test
        void test() {
            Configuration configuration =
                    ConfigurationBuilder.create().withConfigDataType(Root.class).build();

            Root root = configuration.getConfigData(Root.class);
            assertEquals("fromRoot", root.middle().leaf().value());
            assertEquals("fromMiddle", root.middle().leaf().other());
        }

        @ConfigData("root")
        public record Root(
                @ConfigDefault(property = "leaf.value", defaultValue = "fromRoot")
                Middle middle) {}

        @NestedConfig
        public record Middle(
                @ConfigDefault(property = "value", defaultValue = "fromMiddle")
                @ConfigDefault(property = "other", defaultValue = "fromMiddle")
                Leaf leaf) {}

        @NestedConfig
        public record Leaf(String value, String other) {}
    }

    /**
     * A dot separates the segments of the path to a more deeply nested property, and it can just as well be part of a
     * single name that {@link ConfigProperty#value()} defines. Both readings have to be addressable, since a dotted
     * property name is a common way of grouping properties without a nested record.
     */
    @Nested
    class NestedRecordDefaultsForAPropertyNameContainingADot {

        @Test
        void testDefaultIsAppliedToTheLeafWhoseNameContainsTheDot() {
            Configuration configuration =
                    ConfigurationBuilder.create().withConfigDataType(Root.class).build();

            assertEquals(
                    "fromSite", configuration.getConfigData(Root.class).leaf().value());
        }

        @Test
        void testDefinedValueStillBeatsTheDefault() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withValue("root.leaf.foo.bar", "defined")
                    .withConfigDataType(Root.class)
                    .build();

            assertEquals(
                    "defined", configuration.getConfigData(Root.class).leaf().value());
        }

        /**
         * Where both readings resolve there is no way to tell which one was meant, so neither is silently picked.
         */
        @Test
        void testAmbiguousPathIsRejected() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(AmbiguousRoot.class);

            verifyBuildFails(builder, "root.ambiguous.foo.bar", "matches more than one property");
        }

        @ConfigData("root")
        public record Root(
                @ConfigDefault(property = "foo.bar", defaultValue = "fromSite")
                Leaf leaf) {}

        @NestedConfig
        public record Leaf(
                @ConfigProperty(value = "foo.bar") String value) {}

        @ConfigData("root")
        public record AmbiguousRoot(
                @ConfigDefault(property = "foo.bar", defaultValue = "fromSite")
                Ambiguous ambiguous) {}

        @NestedConfig
        public record Ambiguous(
                @ConfigProperty(value = "foo.bar") String flat,
                @ConfigProperty(value = "foo") Nested nested) {}

        @NestedConfig
        public record Nested(@ConfigProperty(value = "bar") String bar) {}
    }

    /**
     * Two annotations may address the same property, and there is no reading of that which is not a mistake: one of the
     * two values is simply dropped. Rejecting it also keeps the runtime and the annotation processor, which documents
     * these defaults, from having to agree on a precedence.
     */
    @Nested
    class DuplicateNestedRecordDefaults {

        @Test
        void testRepeatedAnnotationForOneProperty() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(RepeatedRoot.class);

            verifyBuildFails(builder, "more than one ConfigDefault for the property 'root.leaf.value'");
        }

        @Test
        void testListFormForOneProperty() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(ListRoot.class);

            verifyBuildFails(builder, "more than one ConfigDefault for the property 'root.leaf.value'");
        }

        /**
         * An entry of an enclosing config data object addressing the same property is not a duplicate. It is the
         * documented way of overriding a default from further out and has to keep working.
         */
        @Test
        void testEnclosingAnnotationForTheSamePropertyIsNotADuplicate() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withConfigDataType(EnclosingRoot.class)
                    .build();

            assertEquals(
                    "fromRoot",
                    configuration
                            .getConfigData(EnclosingRoot.class)
                            .middle()
                            .leaf()
                            .value());
        }

        @ConfigData("root")
        public record RepeatedRoot(
                @ConfigDefault(property = "value", defaultValue = "first")
                @ConfigDefault(property = "value", defaultValue = "second")
                Leaf leaf) {}

        @ConfigData("root")
        public record ListRoot(
                @ConfigDefault.List({
                    @ConfigDefault(property = "value", defaultValue = "first"),
                    @ConfigDefault(property = "value", defaultValue = "second")
                })
                Leaf leaf) {}

        @ConfigData("root")
        public record EnclosingRoot(
                @ConfigDefault(property = "leaf.value", defaultValue = "fromRoot")
                Middle middle) {}

        @NestedConfig
        public record Middle(
                @ConfigDefault(property = "value", defaultValue = "fromMiddle")
                Leaf leaf) {}

        @NestedConfig
        public record Leaf(String value) {}
    }

    @Nested
    class NestedRecordDefaultsBeatPropertyDefault {

        @Test
        void test() {
            Configuration configuration =
                    ConfigurationBuilder.create().withConfigDataType(Root.class).build();

            Root root = configuration.getConfigData(Root.class);
            assertEquals("fromSite", root.overridden().value());
            assertEquals("fromRecord", root.untouched().value());
        }

        @ConfigData("root")
        public record Root(
                @ConfigDefault(property = "value", defaultValue = "fromSite")
                Leaf overridden,

                Leaf untouched) {}

        @NestedConfig
        public record Leaf(
                @ConfigProperty(defaultValue = "fromRecord") String value) {}
    }

    @Nested
    class NestedRecordDefaultsRespectPropertyRenaming {

        @Test
        void test() {
            Configuration configuration =
                    ConfigurationBuilder.create().withConfigDataType(Root.class).build();

            Root root = configuration.getConfigData(Root.class);
            assertEquals("fromSite", root.leaf().value());
        }

        @ConfigData("root")
        public record Root(
                @ConfigDefault(property = "renamed", defaultValue = "fromSite")
                Leaf leaf) {}

        @NestedConfig
        public record Leaf(
                @ConfigProperty(value = "renamed") String value) {}
    }

    @Nested
    class NestedRecordDefaultsForCollections {

        /**
         * The property and the value are separate annotation members, so a value can contain the commas that separate
         * the items of a collection.
         */
        @Test
        void test() {
            Configuration configuration =
                    ConfigurationBuilder.create().withConfigDataType(Root.class).build();

            Root root = configuration.getConfigData(Root.class);
            assertIterableEquals(List.of("a", "b", "c"), root.leaf().listProperty());
            assertEquals(Set.of("x", "y", "z"), root.leaf().setProperty());
            assertNull(root.leaf().nullProperty());
        }

        @ConfigData("root")
        public record Root(
                @ConfigDefault(property = "listProperty", defaultValue = "a,b,c")
                @ConfigDefault(property = "setProperty", defaultValue = "x,y,z")
                @ConfigDefault(property = "nullProperty", defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                Leaf leaf) {}

        @NestedConfig
        public record Leaf(List<String> listProperty, Set<String> setProperty, String nullProperty) {}
    }

    @Nested
    class NestedRecordDefaultsAreValidated {

        @Test
        void test() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(Root.class);

            ConfigViolation violation = verifySingleViolation(builder);
            assertEquals("root.leaf.value", violation.getPropertyName());
            assertEquals("-1", violation.getPropertyValue());
        }

        @ConfigData("root")
        public record Root(
                @ConfigDefault(property = "value", defaultValue = "-1")
                Leaf leaf) {}

        @NestedConfig
        public record Leaf(@Positive int value) {}
    }

    /**
     * The runtime decides whether a component holds a group of properties from
     * {@link java.lang.reflect.RecordComponent#getType()}, which is the erasure of the declared type. The annotation
     * processor erases as well, so the constant it generates names the property that is read here.
     */
    @Nested
    class NestedRecordHeldByATypeVariableComponent {

        @Test
        void testTheBoundIsExpandedIntoItsProperties() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withValue("root.leaf.value", "defined")
                    .withConfigDataType(Root.class)
                    .build();

            assertEquals(
                    "defined", configuration.getConfigData(Root.class).leaf().value());
        }

        @Test
        void testThePropertyOfTheGroupIsWhatIsRead() {
            // the name of the component itself is not a property, so setting it leaves the group without a value
            ConfigurationBuilder builder = ConfigurationBuilder.create()
                    .withValue("root.leaf", "defined")
                    .withConfigDataType(Root.class);

            assertThrows(IllegalStateException.class, builder::build);
        }

        @ConfigData("root")
        public record Root<T extends Leaf>(T leaf) {}

        @NestedConfig
        public record Leaf(String value) {}
    }

    @Nested
    class OptionalNestedRecord {

        /**
         * A config source can only define the properties below the component and never the component itself, so whether
         * an optional group is created is decided by the whole group rather than by the name of the component.
         */
        @Test
        void testStaysNullWhenNothingBelowItIsDefined() {
            Configuration configuration =
                    ConfigurationBuilder.create().withConfigDataType(Root.class).build();

            assertNull(configuration.getConfigData(Root.class).leaf());
        }

        @Test
        void testIsCreatedWhenAPropertyBelowItIsDefined() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withValue("root.leaf.value", "defined")
                    .withConfigDataType(Root.class)
                    .build();

            Leaf leaf = configuration.getConfigData(Root.class).leaf();
            assertNotNull(leaf);
            assertEquals("defined", leaf.value());
            assertEquals(1, leaf.count());
        }

        /**
         * Defining one property of the group is what asks for the group, so every other property of it has to resolve
         * to a value like any other property.
         */
        @Test
        void testFailsWhenASiblingPropertyHasNoDefault() {
            ConfigurationBuilder builder = ConfigurationBuilder.create()
                    .withValue("root.withoutDefaults.value", "defined")
                    .withConfigDataType(NoDefaultsRoot.class);

            assertThrows(IllegalStateException.class, builder::build);
        }

        /**
         * A group that is nested inside an optional group is optional in its own right, so defining a property of the
         * outer group does not force the inner one to exist.
         */
        @Test
        void testNestedOptionalGroupIsDecidedOnItsOwn() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withValue("root.outer.value", "defined")
                    .withConfigDataType(DeepRoot.class)
                    .build();

            Outer outer = configuration.getConfigData(DeepRoot.class).outer();
            assertNotNull(outer);
            assertEquals("defined", outer.value());
            assertNull(outer.inner());
        }

        @Test
        void testDeeplyNestedPropertyCreatesEveryGroupAboveIt() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withValue("root.outer.inner.value", "defined")
                    .withConfigDataType(DeepRoot.class)
                    .build();

            Outer outer = configuration.getConfigData(DeepRoot.class).outer();
            assertNotNull(outer);
            assertNotNull(outer.inner());
            assertEquals("defined", outer.inner().value());
        }

        /**
         * A group that the config asks for is created exactly like a group that is not optional, so a default that is
         * defined where the group is used populates a property of it that the config leaves alone.
         */
        @Test
        void testActivatedGroupUsesTheDefaultsDefinedWhereItIsUsed() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withValue("root.leaf.count", "7")
                    .withConfigDataType(UsageSiteDefaultRoot.class)
                    .build();

            UsageSiteLeaf leaf =
                    configuration.getConfigData(UsageSiteDefaultRoot.class).leaf();
            assertNotNull(leaf);
            assertEquals(7, leaf.count());
            assertEquals("fromUsageSite", leaf.value());
        }

        /**
         * Whether the group is created is decided by what a config source defines, and a default is not a definition. A
         * group whose every property has a default therefore still stays null, which is what keeps it optional.
         */
        @Test
        void testDefaultsDefinedWhereTheGroupIsUsedDoNotActivateIt() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withConfigDataType(UsageSiteDefaultRoot.class)
                    .build();

            assertNull(configuration.getConfigData(UsageSiteDefaultRoot.class).leaf());
        }

        @ConfigData("root")
        public record Root(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                Leaf leaf) {}

        @NestedConfig
        public record Leaf(
                @ConfigProperty(defaultValue = "fromRecord") String value,
                @ConfigProperty(defaultValue = "1") int count) {}

        @ConfigData("root")
        public record NoDefaultsRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                NoDefaultsLeaf withoutDefaults) {}

        @NestedConfig
        public record NoDefaultsLeaf(String value, int mandatory) {}

        @ConfigData("root")
        public record DeepRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                Outer outer) {}

        @NestedConfig
        public record Outer(
                @ConfigProperty(defaultValue = "outerDefault")
                String value,

                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                Leaf inner) {}

        @ConfigData("root")
        public record UsageSiteDefaultRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                @ConfigDefault(property = "value", defaultValue = "fromUsageSite")
                @ConfigDefault(property = "count", defaultValue = "1")
                UsageSiteLeaf leaf) {}

        @NestedConfig
        public record UsageSiteLeaf(String value, int count) {}
    }

    /**
     * Absent is the normal state of an optional group, so a group that is only declared wrongly would build on every
     * node until a config defines one property below it. Every mistake that follows from the declaration alone is
     * therefore reported while the group is absent as well, which is what every test here relies on: none of them
     * defines a property below the group.
     * <p>
     * What does not follow from the declaration alone is whether a property can resolve to a value, since that is what
     * the config decides. A leaf without a default is accepted here and only fails once the group is asked for.
     */
    @Nested
    class AbsentOptionalNestedRecordIsStillValidated {

        @Test
        void testConfigDefaultThatMatchesNoProperty() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(UnknownEntryRoot.class);

            verifyBuildFails(builder, "root.leaf.typo", "does not match any property");
        }

        @Test
        void testConfigDefaultUsingTheRecordComponentNameOfARenamedProperty() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(RenamedPropertyRoot.class);

            verifyBuildFails(builder, "root.leaf.value", "Known properties: [root.leaf.renamed]");
        }

        @Test
        void testConfigDefaultOfAGroupThatIsItselfNestedInTheAbsentGroup() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(DeepEntryRoot.class);

            verifyBuildFails(builder, "root.outer.inner.typo", "does not match any property");
        }

        @Test
        void testCircularReference() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(CircularRoot.class);

            verifyCircularReferenceException(builder);
        }

        @Test
        void testNestedRecordThatIsNotPublic() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(NotPublicRoot.class);

            verifyBuildFails(builder, "is not public");
        }

        /**
         * The mistakes below are about a component of the absent group rather than about the group itself, so they are
         * the ones a validation that only walks the nested records would miss. Each of them is rejected for a group
         * that is created by {@link InvalidNestedRecordDefaults}, and the message has to be the same one: it is the
         * declaration that is wrong, not the configuration.
         */
        @Test
        void testComponentThatIsNeitherNestedNorConverted() {
            ConfigurationBuilder builder =
                    ConfigurationBuilder.create().withConfigDataType(UnmarkedComponentRoot.class);

            verifyBuildFails(builder, "is neither annotated with NestedConfig", "nor has a converter registered");
        }

        @Test
        void testConfigDefaultOnAComponentThatIsNotANestedRecord() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(ScalarDefaultRoot.class);

            verifyBuildFails(builder, "root.group.value", "is not a nested config data object");
        }

        @Test
        void testNestedRecordThatAlsoHasAConverter() {
            ConfigurationBuilder builder = ConfigurationBuilder.create()
                    .withConfigDataType(ConvertedComponentRoot.class)
                    .withConverter(Leaf.class, _ -> new Leaf("converted"));

            verifyBuildFails(builder, "also has a converter", "Remove one of the two");
        }

        @Test
        void testNonNullDefaultOnANestedComponent() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(NonNullDefaultRoot.class);

            verifyBuildFails(builder, "root.group.leaf", "is a nested config data object");
        }

        /**
         * A group that is created converts the default of every property of it before the config is even asked, so a
         * default that can not be converted is a mistake in the declaration rather than something the configuration
         * decides. It is therefore reported while the group is absent as well.
         */
        @Test
        void testLeafDefaultThatCanNotBeConverted() {
            ConfigurationBuilder builder =
                    ConfigurationBuilder.create().withConfigDataType(UnconvertibleLeafDefaultRoot.class);

            verifyBuildFails(builder, "Can not convert to", "int");
        }

        @Test
        void testUsageSiteDefaultThatCanNotBeConverted() {
            ConfigurationBuilder builder =
                    ConfigurationBuilder.create().withConfigDataType(UnconvertibleConfigDefaultRoot.class);

            verifyBuildFails(builder, "Can not convert to", "int");
        }

        @Test
        void testLeafDefaultOfAGroupThatIsItselfNestedInTheAbsentGroup() {
            ConfigurationBuilder builder =
                    ConfigurationBuilder.create().withConfigDataType(UnconvertibleDeepDefaultRoot.class);

            verifyBuildFails(builder, "Can not convert to", "int");
        }

        /**
         * An enclosing config data record wins over a default that is declared closer to the property, so it is the
         * value that arrives at the property which has to be checked, not both of them.
         */
        @Test
        void testUsageSiteDefaultThatRepairsAnUnconvertibleLeafDefault() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withConfigDataType(RepairedLeafDefaultRoot.class)
                    .build();

            assertNull(
                    configuration.getConfigData(RepairedLeafDefaultRoot.class).leaf());
        }

        /**
         * A leaf that declares no default is a property the config has to define. Requiring a default while the group is
         * absent would make an optional group of mandatory properties impossible, so it stays a mistake that is only
         * reported once the group is asked for.
         */
        @Test
        void testLeafWithoutADefaultIsAccepted() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withConfigDataType(MandatoryLeafRoot.class)
                    .build();

            assertNull(configuration.getConfigData(MandatoryLeafRoot.class).leaf());
        }

        /**
         * The validation must not reject a group that is merely absent, which is by far the common case.
         */
        @Test
        void testCorrectlyDeclaredGroupStillStaysNull() {
            Configuration configuration = ConfigurationBuilder.create()
                    .withConfigDataType(ValidRoot.class)
                    .build();

            assertNull(configuration.getConfigData(ValidRoot.class).leaf());
        }

        @ConfigData("root")
        public record UnknownEntryRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                @ConfigDefault(property = "typo", defaultValue = "1")
                Leaf leaf) {}

        @ConfigData("root")
        public record RenamedPropertyRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                @ConfigDefault(property = "value", defaultValue = "1")
                RenamedLeaf leaf) {}

        @ConfigData("root")
        public record DeepEntryRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                Outer outer) {}

        @NestedConfig
        public record Outer(
                @ConfigDefault(property = "typo", defaultValue = "1")
                Leaf inner) {}

        @ConfigData("root")
        public record CircularRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                Recursive recursive) {}

        @NestedConfig
        public record Recursive(Recursive again) {}

        @ConfigData("root")
        public record NotPublicRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                NotPublicLeaf leaf) {}

        @NestedConfig
        record NotPublicLeaf(String value) {}

        @ConfigData("root")
        public record ValidRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                @ConfigDefault(property = "value", defaultValue = "1")
                Leaf leaf) {}

        @ConfigData("root")
        public record UnmarkedComponentRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                UnmarkedComponentGroup group) {}

        @NestedConfig
        public record UnmarkedComponentGroup(UnmarkedLeaf leaf) {}

        public record UnmarkedLeaf(String value) {}

        @ConfigData("root")
        public record ScalarDefaultRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                ScalarDefaultGroup group) {}

        @NestedConfig
        public record ScalarDefaultGroup(
                @ConfigDefault(property = "whatever", defaultValue = "1")
                String value) {}

        @ConfigData("root")
        public record ConvertedComponentRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                ConvertedComponentGroup group) {}

        @NestedConfig
        public record ConvertedComponentGroup(Leaf leaf) {}

        @ConfigData("root")
        public record NonNullDefaultRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                NonNullDefaultGroup group) {}

        @NestedConfig
        public record NonNullDefaultGroup(
                @ConfigProperty(defaultValue = "whatever") Leaf leaf) {}

        @ConfigData("root")
        public record UnconvertibleLeafDefaultRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                UnconvertibleLeaf leaf) {}

        @NestedConfig
        public record UnconvertibleLeaf(
                @ConfigProperty(defaultValue = "notANumber") int count) {}

        @ConfigData("root")
        public record UnconvertibleConfigDefaultRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                @ConfigDefault(property = "count", defaultValue = "notANumber")
                MandatoryLeaf leaf) {}

        @ConfigData("root")
        public record UnconvertibleDeepDefaultRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                UnconvertibleOuter outer) {}

        @NestedConfig
        public record UnconvertibleOuter(UnconvertibleLeaf inner) {}

        @ConfigData("root")
        public record RepairedLeafDefaultRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                @ConfigDefault(property = "count", defaultValue = "7")
                UnconvertibleLeaf leaf) {}

        @ConfigData("root")
        public record MandatoryLeafRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                MandatoryLeaf leaf) {}

        @NestedConfig
        public record MandatoryLeaf(int count) {}

        @NestedConfig
        public record Leaf(
                @ConfigProperty(defaultValue = "fromRecord") String value) {}

        @NestedConfig
        public record RenamedLeaf(
                @ConfigProperty(value = "renamed") String value) {}
    }

    /**
     * A nested config data object takes its name from the single component that holds it, so an element of a collection
     * has no property name a config source could use. Without this being rejected the collection is read as a single
     * property whose elements a converter creates, and the failure names the missing converter, which a nested config
     * data object must not have in the first place.
     */
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
         * The mistake is in the declaration, so it is reported for a group that the config never asks for as well.
         */
        @Test
        void testCollectionInAnAbsentOptionalGroupIsRejected() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(OptionalListRoot.class);

            verifyBuildFails(builder, "root.group.leaves", NestedConfig.class.getSimpleName());
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
        public record OptionalListRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                Group group) {}

        @NestedConfig
        public record Group(List<Leaf> leaves) {}

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
         * An optional group is normally absent, so the same mistake has to be reported without the group being created.
         */
        @Test
        void testRecordWithBothAnnotationsIsRejectedAsAnAbsentOptionalComponent() {
            ConfigurationBuilder builder =
                    ConfigurationBuilder.create().withConfigDataType(OptionalBothAnnotationsRoot.class);

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

        @ConfigData("root")
        public record OptionalBothAnnotationsRoot(
                @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                BothAnnotations nested) {}
    }

    @Nested
    class InvalidNestedRecordDefaults {

        @Test
        void testEntryThatMatchesNoProperty() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(UnknownEntryRoot.class);

            verifyBuildFails(builder, "root.leaf.typo");
        }

        /**
         * A property is addressed by its config name, so using the name of the record component of a renamed property
         * is the mistake most likely to be made. The error has to name the property that does exist.
         */
        @Test
        void testEntryUsingTheRecordComponentNameOfARenamedProperty() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(RenamedPropertyRoot.class);

            verifyBuildFails(builder, "root.leaf.value", "Known properties: [root.leaf.renamed]");
        }

        /**
         * The marker is what {@link ConfigProperty#defaultValue()} uses to say that no default is defined, so a
         * {@link ConfigDefault} carrying it says nothing while overriding the default that the property declares
         * itself. Storing it would assign its text as the value of the property.
         */
        @Test
        void testUndefinedDefaultValueMarkerIsRejected() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(UndefinedMarkerRoot.class);

            verifyBuildFails(builder, "root.leaf.value", "UNDEFINED_DEFAULT_VALUE", "no default is defined");
        }

        @ConfigData("root")
        public record UndefinedMarkerRoot(
                @ConfigDefault(property = "value", defaultValue = ConfigProperty.UNDEFINED_DEFAULT_VALUE)
                Leaf leaf) {}

        @Test
        void testAnnotationOnComponentThatIsNotANestedRecord() {
            ConfigurationBuilder builder = ConfigurationBuilder.create()
                    .withValue("root.value", "x")
                    .withConfigDataType(NotANestedRecordRoot.class);

            verifyBuildFails(builder, "is not a nested config data object");
        }

        @Test
        void testDefaultValueForNestedRecordWithoutConverter() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(DefaultValueRoot.class);

            verifyBuildFails(builder, "is a nested config data object");
        }

        @ConfigData("root")
        public record UnknownEntryRoot(
                @ConfigDefault(property = "value", defaultValue = "1")
                @ConfigDefault(property = "typo", defaultValue = "2")
                Leaf leaf) {}

        @ConfigData("root")
        public record RenamedPropertyRoot(
                @ConfigDefault(property = "value", defaultValue = "1")
                RenamedLeaf leaf) {}

        @NestedConfig
        public record RenamedLeaf(
                @ConfigProperty(value = "renamed") String value) {}

        @ConfigData("root")
        public record NotANestedRecordRoot(
                @ConfigDefault(property = "value", defaultValue = "1")
                String value) {}

        @Test
        void testRecordComponentThatIsNeitherNestedNorConverted() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(UnmarkedRecordRoot.class);

            verifyBuildFails(builder, "is neither annotated with NestedConfig", "nor has a converter registered");
        }

        @ConfigData("root")
        public record UnmarkedRecordRoot(UnmarkedLeaf leaf) {}

        public record UnmarkedLeaf(String value) {}

        @ConfigData("root")
        public record DefaultValueRoot(
                @ConfigProperty(defaultValue = "whatever") Leaf leaf) {}

        @Test
        void testEmptyProperty() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(EmptyPropertyRoot.class);

            verifyBuildFails(builder, "does not match any property", "Known properties: [root.leaf.value]");
        }

        @Test
        void testPropertyWithATrailingDot() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(TrailingDotRoot.class);

            verifyBuildFails(builder, "does not match any property");
        }

        /**
         * A nested config data object has no value of its own, so a default value can only be defined for one of its
         * properties. Addressing the group would otherwise silently null it out via
         * {@link ConfigProperty#NULL_DEFAULT_VALUE}.
         */
        @Test
        void testPropertyAddressingANestedConfigInsteadOfALeaf() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(AddressesGroupRoot.class);

            verifyBuildFails(
                    builder,
                    "addresses a nested config data object instead of a single property",
                    "root.middle.leaf.value");
        }

        @ConfigData("root")
        public record EmptyPropertyRoot(
                @ConfigDefault(property = "", defaultValue = "1")
                Leaf leaf) {}

        @ConfigData("root")
        public record TrailingDotRoot(
                @ConfigDefault(property = "value.", defaultValue = "1")
                Leaf leaf) {}

        @ConfigData("root")
        public record AddressesGroupRoot(
                @ConfigDefault(property = "leaf", defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
                Middle middle) {}

        @NestedConfig
        public record Middle(Leaf leaf) {}

        @NestedConfig
        public record Leaf(String value) {}
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
        return verifyViolations(builder, 1).get(0);
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
