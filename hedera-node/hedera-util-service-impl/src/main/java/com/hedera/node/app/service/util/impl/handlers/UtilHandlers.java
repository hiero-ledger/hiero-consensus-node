// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl.handlers;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class UtilHandlers {

    private final UtilPrngHandler prngHandler;
    private final AtomicBatchHandler atomicBatchHandler;

    @Inject
    public UtilHandlers(
            @NonNull final UtilPrngHandler prngHandler, @NonNull final AtomicBatchHandler atomicBatchHandler) {
        this.prngHandler = Objects.requireNonNull(prngHandler, "prngHandler must not be null");
        this.atomicBatchHandler = Objects.requireNonNull(atomicBatchHandler, "atomicBatchHandler must not be null");
    }

    public UtilPrngHandler prngHandler() {
        return prngHandler;
    }

    public AtomicBatchHandler atomicBatchHandler() {
        return atomicBatchHandler;
    }
}
