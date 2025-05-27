// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers.helpers;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import org.hiero.base.concurrent.ThrowingRunnable;

public record ThrowingRunnableWrapper(ThrowingRunnable runnable, Consumer<Throwable> throwableConsumer)
        implements Runnable {

    static void runWrappingChecked(@NonNull final ThrowingRunnable runnable) {
        new ThrowingRunnableWrapper(runnable, (t) -> {
                    throw new RuntimeException(t);
                })
                .run();
    }

    @Override
    public void run() {
        try {
            runnable.run();
        } catch (final Throwable t) {
            throwableConsumer.accept(t);
        }
    }
}
