// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.otter.fixtures.FalconTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link ArgumentSets}.
 */
@DisplayName("Argument sets Test")
class ArgumentSetsTest {

    @Test
    @DisplayName("A test method without an argument source has a single, empty argument set")
    void noSourceGivesTheEmptyArgumentSet() {
        assertThat(ArgumentSets.of(contextFor("noSource"))).containsExactly(ArgumentSet.EMPTY);
    }

    @Test
    @DisplayName("Arguments returned by a factory method are unwrapped, and Named arguments are labelled by name")
    void argumentsAreUnwrappedAndLabelled() {
        final List<ArgumentSet> argumentSets = ArgumentSets.of(contextFor("fromArguments"));

        assertThat(argumentSets).extracting(ArgumentSet::values).containsExactly(List.of(1, "x"), List.of(2, "y"));
        assertThat(argumentSets).extracting(ArgumentSet::label).containsExactly("1, one", "2, y");
    }

    @Test
    @DisplayName("An empty @MethodSource refers to the factory method named like the test method")
    void emptyMethodSourceUsesFactoryNamedLikeTheTest() {
        assertThat(ArgumentSets.of(contextFor("defaultFactory")))
                .extracting(ArgumentSet::values)
                .containsExactly(List.of(1), List.of(2));
    }

    @Test
    @DisplayName("A factory method may return a stream, a primitive stream, an iterable, an iterator or an array")
    void factoryMethodsMayReturnAllSupportedTypes() {
        assertThat(ArgumentSets.of(contextFor("returnTypes")))
                .extracting(ArgumentSet::values)
                .containsExactly(List.of(1), List.of(2), List.of(3), List.of(4), List.of(5), List.of(6));
    }

    @Test
    @DisplayName("An Object[] element of a factory method provides several values")
    void objectArrayElementProvidesSeveralValues() {
        assertThat(ArgumentSets.of(contextFor("fromObjectArrays")))
                .extracting(ArgumentSet::values)
                .containsExactly(List.of(1, "a"));
    }

    @Test
    @DisplayName("Every element of a @ValueSource is an argument set with a single value")
    void valueSourceProvidesOneArgumentSetPerElement() {
        final List<ArgumentSet> argumentSets = ArgumentSets.of(contextFor("fromValueSource"));

        assertThat(argumentSets).extracting(ArgumentSet::values).containsExactly(List.of(1), List.of(2), List.of(3));
        assertThat(argumentSets).extracting(ArgumentSet::label).containsExactly("1", "2", "3");
    }

    @Test
    @DisplayName("The argument sets of several sources are combined")
    void severalSourcesAreCombined() {
        assertThat(ArgumentSets.of(contextFor("fromTwoSources")))
                .extracting(ArgumentSet::values)
                .containsExactlyInAnyOrder(List.of(1), List.of(2));
    }

    @Test
    @DisplayName("A null value can be passed to a parameter that is not primitive")
    void nullValueForObjectParameter() {
        assertThat(ArgumentSets.of(contextFor("nullForObject")))
                .extracting(ArgumentSet::values)
                .containsExactly(Collections.singletonList(null));
    }

    @ParameterizedTest
    @DisplayName("Unsupported or mismatching argument sources are rejected")
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            value = {
                "csvSource                  | unsupported argument source @CsvSource",
                "enumSource                 | unsupported argument source @EnumSource",
                "externalFactory            | only factory methods without parameters in the test class",
                "missingFactory             | 'missing', which does not exist",
                "nonStaticFactory           | 'instanceFactory', which is not static",
                "unsupportedReturnType      | must return a Stream, an Iterable, an Iterator or an array",
                "emptyFactory               | must provide at least one argument set",
                "twoValueSourceAttributes   | must set exactly one attribute of @ValueSource, but 2 are set",
                "noValueSourceAttribute     | must set exactly one attribute of @ValueSource, but 0 are set",
                "tooManyValues              | receives 2 values in argument set 1 [1, 2], but declares only 1",
                "environmentFirst           | binds argument set 1 [1] to its TestEnvironment parameter",
                "typeMismatch               | of type java.lang.String to a parameter of type int",
                "noWidening                 | of type java.lang.Integer to a parameter of type long",
                "nullForPrimitive           | of type null to a parameter of type int",
            })
    void invalidSourcesAreRejected(final String testMethod, final String expectedMessage) {
        assertThatThrownBy(() -> ArgumentSets.of(contextFor(testMethod)))
                .isInstanceOf(ExtensionConfigurationException.class)
                .hasMessageContaining(expectedMessage);
    }

    @Test
    @DisplayName("A test method annotated with @ParameterizedTest is rejected")
    void parameterizedTestIsRejected() {
        assertThatThrownBy(() -> ArgumentSets.rejectParameterizedTest(contextFor("parameterized"), FalconTest.class))
                .isInstanceOf(ExtensionConfigurationException.class)
                .hasMessageContaining("must not combine @FalconTest with @ParameterizedTest");
        assertThatCode(() -> ArgumentSets.rejectParameterizedTest(contextFor("fromValueSource"), FalconTest.class))
                .doesNotThrowAnyException();
    }

    /**
     * Creates an extension context that reports the given sample method as the test method.
     */
    private static ExtensionContext contextFor(final String testMethod) {
        // Every sample test method has parameters, which tells it apart from a factory method of the same name
        final Method method = Arrays.stream(Samples.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(testMethod) && candidate.getParameterCount() > 0)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No sample method named " + testMethod));
        final ExtensionContext context = mock(ExtensionContext.class);
        when(context.getElement()).thenReturn(Optional.of(method));
        when(context.getRequiredTestMethod()).thenReturn(method);
        doReturn(Samples.class).when(context).getRequiredTestClass();
        return context;
    }

    /**
     * Holds the test methods and factory methods under test. This class is deliberately private and not a nested test
     * class, so that JUnit does not discover the methods below as tests.
     */
    @SuppressWarnings("unused")
    private static final class Samples {

        void noSource(final TestEnvironment env) {}

        @MethodSource("arguments")
        void fromArguments(final int number, final String text, final TestEnvironment env) {}

        static Stream<Arguments> arguments() {
            return Stream.of(Arguments.of(1, Named.of("one", "x")), Arguments.of(2, "y"));
        }

        @MethodSource
        void defaultFactory(final int number) {}

        static List<Integer> defaultFactory() {
            return List.of(1, 2);
        }

        @MethodSource({"stream", "intStream", "iterable", "iterator", "objectArray", "intArray"})
        void returnTypes(final int number) {}

        static Stream<Integer> stream() {
            return Stream.of(1);
        }

        static IntStream intStream() {
            return IntStream.of(2);
        }

        static Iterable<Integer> iterable() {
            return List.of(3);
        }

        static Iterator<Integer> iterator() {
            return List.of(4).iterator();
        }

        static Integer[] objectArray() {
            return new Integer[] {5};
        }

        static int[] intArray() {
            return new int[] {6};
        }

        @MethodSource("objectArrays")
        void fromObjectArrays(final int number, final String text) {}

        static Stream<Object[]> objectArrays() {
            return Stream.<Object[]>of(new Object[] {1, "a"});
        }

        @ValueSource(ints = {1, 2, 3})
        void fromValueSource(final int number, final TestEnvironment env) {}

        @MethodSource("one")
        @ValueSource(ints = 2)
        void fromTwoSources(final int number) {}

        static Stream<Integer> one() {
            return Stream.of(1);
        }

        @MethodSource("nullValue")
        void nullForObject(final String text) {}

        static Stream<Arguments> nullValue() {
            return Stream.of(Arguments.of((Object) null));
        }

        @CsvSource("1, a")
        void csvSource(final int number, final String text) {}

        @EnumSource(TimeUnit.class)
        void enumSource(final TimeUnit unit) {}

        @MethodSource("org.hiero.otter.fixtures.junit.ArgumentSetsTest$Samples#one")
        void externalFactory(final int number) {}

        @MethodSource("missing")
        void missingFactory(final int number) {}

        @MethodSource("instanceFactory")
        void nonStaticFactory(final int number) {}

        Stream<Integer> instanceFactory() {
            return Stream.of(1);
        }

        @MethodSource("text")
        void unsupportedReturnType(final int number) {}

        static String text() {
            return "1";
        }

        @MethodSource("nothing")
        void emptyFactory(final int number) {}

        static Stream<Integer> nothing() {
            return Stream.empty();
        }

        @ValueSource(ints = 1, strings = "a")
        void twoValueSourceAttributes(final int number) {}

        @ValueSource
        void noValueSourceAttribute(final int number) {}

        @MethodSource("pairs")
        void tooManyValues(final int number) {}

        static Stream<Arguments> pairs() {
            return Stream.of(Arguments.of(1, 2));
        }

        @ValueSource(ints = 1)
        void environmentFirst(final TestEnvironment env, final int number) {}

        @ValueSource(strings = "1")
        void typeMismatch(final int number) {}

        @ValueSource(ints = 1)
        void noWidening(final long number) {}

        @MethodSource("nullValue")
        void nullForPrimitive(final int number) {}

        @ParameterizedTest
        @ValueSource(ints = 1)
        void parameterized(final int number) {}
    }
}
