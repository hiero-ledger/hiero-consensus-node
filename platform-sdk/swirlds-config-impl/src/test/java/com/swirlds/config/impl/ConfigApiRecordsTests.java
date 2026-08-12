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
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.ConfigViolationException;
import com.swirlds.config.api.validation.annotation.ConstraintMethod;
import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.config.api.validation.annotation.Min;
import com.swirlds.config.api.validation.annotation.Positive;
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
        assertTrue(exception.getViolations().get(0).propertyExists());
        assertEquals("network.port", exception.getViolations().get(0).getPropertyName());
        assertEquals("-1", exception.getViolations().get(0).getPropertyValue());
        assertEquals("Value must be >= 1", exception.getViolations().get(0).getMessage());
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
    class RootRecordCircularReference {

        @Test
        void test() {
            ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(Root.class);

            verifyCircularReferenceException(builder);
        }

        @ConfigData("circular")
        public record Root(Nested nested) {}

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

        public record Nested1(Nested2 nested2) {}

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

        public record Level1(Level2 level2) {}

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
