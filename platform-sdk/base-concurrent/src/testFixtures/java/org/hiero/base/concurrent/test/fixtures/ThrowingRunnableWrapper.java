// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.test.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import org.hiero.base.concurrent.ThrowingRunnable;

/**
 * A Wrapper that allows to convert a {@link ThrowingRunnable} into a {@link Runnable} by allowing to consume a {@link Throwable} T
 * @param runnable the throwing runnable to wrap
 * @param throwableConsumer how to process the exception
 */
public record ThrowingRunnableWrapper(
        @NonNull ThrowingRunnable runnable, @NonNull Consumer<Throwable> throwableConsumer) implements Runnable {

    /**
     * Creates a default wrapper that wraps the throwable in a {@link RuntimeException}
     * @param runnable the ThrowingRunnable to wrap
     */
    static void runWrappingChecked(@NonNull final ThrowingRunnable runnable) {
        new ThrowingRunnableWrapper(runnable, (t) -> {
                    throw new RuntimeException(t);
                })
                .run();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        try {
            runnable.run();
        } catch (final Throwable t) {
            throwableConsumer.accept(t);
        }
    }
}
