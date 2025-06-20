// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.fixtures.assertions;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.InstanceOfAssertFactory;

/**
 * A collection of assertion methods for use in tests.
 */
public class AssertionUtils {

    /**
     * Asserts that the {@link Throwable} of the given {@link AbstractThrowableAssert} has a non-null message.
     *
     * @param abstractThrowableAssert the assert to check
     */
    private static void assertHasAnyMessage(
            @NonNull final AbstractThrowableAssert<?, ? extends Throwable> abstractThrowableAssert) {
        if (abstractThrowableAssert == null) {
            throw new NullPointerException("abstractThrowableAssert must not be null");
        }
        abstractThrowableAssert
                .extracting(Throwable::getMessage, asString())
                .isNotEmpty()
                .isNotBlank()
                .isNotNull();
    }

    /**
     * Returns an {@link InstanceOfAssertFactory} for {@link String} instances: {@link  AbstractStringAssert}
     *
     * @return the factory
     */
    @NonNull
    private static InstanceOfAssertFactory<String, AbstractStringAssert<?>> asString() {
        return as(InstanceOfAssertFactories.STRING);
    }

    /**
     * Asserts that the given {@link Throwable} has a non-null message.
     *
     * @param t the throwable to check
     */
    private static void assertHasAnyMessage(@NonNull final Throwable t) {
        assertHasAnyMessage(assertThat(t).isNotNull());
    }

    /**
     * Asserts that the given {@link Throwable} is an instance of the given {@link Class} and has a non-null message.
     *
     * @param throwableClass the class of the expected throwable
     * @param runnable       the runnable to check
     * @param <T>            the type of the expected throwable
     */
    public static <T extends Throwable> void assertThrowsWithMessage(
            @NonNull final Class<T> throwableClass, @NonNull final Runnable runnable) {
        Objects.requireNonNull(throwableClass, "throwableClass must not be null");
        Objects.requireNonNull(runnable, "runnable must not be null");
        try {
            runnable.run();
            fail("Expected an exception to be thrown");
        } catch (final Throwable t) {
            assertThat(t).isInstanceOf(throwableClass);
            assertHasAnyMessage(t);
        }
    }

    /**
     * Asserts that the given {@link Runnable} throws an {@link NullPointerException} with a non-null message.
     *
     * @param runnable the runnable to check
     */
    public static void assertThrowsNPE(@NonNull final Runnable runnable) {
        assertThrowsWithMessage(NullPointerException.class, runnable);
    }
}
