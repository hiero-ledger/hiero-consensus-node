// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.futures;

import com.swirlds.logging.legacy.LogMarker;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.hiero.base.utility.exceptions.PlatformException;

public class FuturePool<V> extends LinkedList<Future<V>> {

    private final Consumer<Exception> exceptionHandler;

    private static volatile boolean shuttingDown = false;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shuttingDown = true;
        }));
    }

    /**
     * Constructs an empty list.
     */
    public FuturePool() {
        exceptionHandler = null;
    }

    /**
     * Constructs an empty list.
     *
     * @param exceptionHandler
     * 		an handler which handles exceptions thrown during the computation
     */
    public FuturePool(final Consumer<Exception> exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * Constructs a list containing the elements of the specified collection, in the order they are returned by the
     * collection's iterator.
     *
     * @param c
     * 		the collection whose elements are to be placed into this list
     * @param exceptionHandler
     * 		an handler which handles exceptions thrown during the computation
     * @throws NullPointerException
     * 		if the specified collection is null
     */
    public FuturePool(final Collection<? extends Future<V>> c, final Consumer<Exception> exceptionHandler) {
        super(c);
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * Constructs a list containing the elements of the specified collection, in the order they are returned by the
     * collection's iterator.
     *
     * @param c
     * 		the collection whose elements are to be placed into this list
     * @throws NullPointerException
     * 		if the specified collection is null
     */
    public FuturePool(final Collection<? extends Future<V>> c) {
        super(c);
        this.exceptionHandler = null;
    }

    /**
     * Determines if all futures have been completed or cancelled.
     *
     * @return true if all futures are completed or cancelled, otherwise false
     */
    public boolean isComplete() {
        removeIf((v) -> v.isDone() || v.isCancelled());
        return size() == 0;
    }

    /**
     * Getter that returns true if the shutdown hook has been called by the JVM.
     *
     * @return true if the JVM is shutting down; false otherwise
     */
    private static boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * Waits (indefinitely) for all futures to either complete or be cancelled.
     *
     * @return an ordered {@link List} containing all values (or {@code null} if cancelled) returned by the futures
     * 		contained in this {@link FuturePool}.
     */
    public List<V> waitForCompletion() {
        final List<V> results = new ArrayList<>(size());

        forEach((f) -> {
            if (f.isCancelled()) {
                results.add(null);
                return;
            }

            try {
                results.add(f.get());
            } catch (InterruptedException | ExecutionException ex) {
                if (exceptionHandler != null) {
                    if (!isShuttingDown()) {
                        exceptionHandler.accept(ex);
                    }
                } else {
                    if (!isShuttingDown()) {

                        if (ex instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }

                        throw new PlatformException(ex, LogMarker.EXCEPTION);
                    }
                }
            }
        });

        return results;
    }
}
