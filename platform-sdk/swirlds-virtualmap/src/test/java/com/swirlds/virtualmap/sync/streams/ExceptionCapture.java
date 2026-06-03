// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync.streams;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

public class ExceptionCapture implements Function<Throwable, Boolean> {

    private final ConcurrentLinkedQueue<Throwable> exceptions = new ConcurrentLinkedQueue<>();

    @Override
    public Boolean apply(final Throwable throwable) {
        // StandardWorkGroup may wrap user exceptions in ExecutionException; unwrap for assertions.
        if (throwable instanceof ExecutionException && throwable.getCause() != null) {
            exceptions.add(throwable.getCause());
        } else {
            exceptions.add(throwable);
        }
        return true;
    }

    public ConcurrentLinkedQueue<Throwable> getExceptions() {
        return exceptions;
    }
}
