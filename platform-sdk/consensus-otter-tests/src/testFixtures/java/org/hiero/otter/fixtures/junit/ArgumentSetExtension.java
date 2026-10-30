// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * A JUnit 5 extension that binds the values of an {@link ArgumentSet} to the leading parameters of the test method.
 *
 * <p>One instance is created per invocation. The values have been validated against the parameter types by
 * {@link ArgumentSets}, so they are passed on as they are.
 */
final class ArgumentSetExtension implements ParameterResolver {

    private final ArgumentSet argumentSet;

    /**
     * Constructor for {@link ArgumentSetExtension}.
     *
     * @param argumentSet the argument set of this invocation
     */
    ArgumentSetExtension(@NonNull final ArgumentSet argumentSet) {
        this.argumentSet = requireNonNull(argumentSet);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportsParameter(
            @NonNull final ParameterContext parameterContext, @NonNull final ExtensionContext extensionContext) {
        return parameterContext.getDeclaringExecutable().equals(extensionContext.getRequiredTestMethod())
                && parameterContext.getIndex() < argumentSet.values().size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public Object resolveParameter(
            @NonNull final ParameterContext parameterContext, @NonNull final ExtensionContext extensionContext) {
        return argumentSet.values().get(parameterContext.getIndex());
    }
}
