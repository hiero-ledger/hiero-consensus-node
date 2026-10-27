// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import org.hiero.otter.fixtures.TestEnvironment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;

/**
 * Tests {@link ArgumentSetExtension}.
 */
@DisplayName("Argument set extension Test")
class ArgumentSetExtensionTest {

    private final ArgumentSetExtension extension = new ArgumentSetExtension(ArgumentSet.of(4, null));

    @Test
    @DisplayName("The values bind to the leading parameters of the test method")
    void valuesBindToLeadingParameters() throws NoSuchMethodException {
        final Method testMethod = testMethod();
        final ExtensionContext extensionContext = extensionContextFor(testMethod);

        assertThat(extension.supportsParameter(parameterContext(testMethod, 0), extensionContext))
                .isTrue();
        assertThat(extension.resolveParameter(parameterContext(testMethod, 0), extensionContext))
                .isEqualTo(4);
        assertThat(extension.supportsParameter(parameterContext(testMethod, 1), extensionContext))
                .isTrue();
        assertThat(extension.resolveParameter(parameterContext(testMethod, 1), extensionContext))
                .isNull();
    }

    @Test
    @DisplayName("Parameters after the values are left to other resolvers")
    void parametersAfterTheValuesAreNotSupported() throws NoSuchMethodException {
        final Method testMethod = testMethod();

        assertThat(extension.supportsParameter(parameterContext(testMethod, 2), extensionContextFor(testMethod)))
                .isFalse();
    }

    @Test
    @DisplayName("Parameters of other executables, such as a constructor, are left to other resolvers")
    void parametersOfOtherExecutablesAreNotSupported() throws NoSuchMethodException {
        final Executable constructor = Samples.class.getDeclaredConstructor();

        assertThat(extension.supportsParameter(parameterContext(constructor, 0), extensionContextFor(testMethod())))
                .isFalse();
    }

    private static Method testMethod() throws NoSuchMethodException {
        return Samples.class.getDeclaredMethod("test", int.class, String.class, TestEnvironment.class);
    }

    private static ExtensionContext extensionContextFor(final Method testMethod) {
        final ExtensionContext context = mock(ExtensionContext.class);
        when(context.getRequiredTestMethod()).thenReturn(testMethod);
        return context;
    }

    private static ParameterContext parameterContext(final Executable executable, final int index) {
        final ParameterContext context = mock(ParameterContext.class);
        when(context.getDeclaringExecutable()).thenReturn(executable);
        when(context.getIndex()).thenReturn(index);
        return context;
    }

    /**
     * Holds the test method under test. This class is deliberately private, so that JUnit does not discover it.
     */
    @SuppressWarnings("unused")
    private static final class Samples {

        void test(final int number, final String text, final TestEnvironment env) {}
    }
}
