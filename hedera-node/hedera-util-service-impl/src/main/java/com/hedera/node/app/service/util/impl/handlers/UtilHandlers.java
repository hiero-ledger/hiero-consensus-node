// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl.handlers;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class UtilHandlers {

    private final UtilPrngHandler prngHandler;

    @Inject
    public UtilHandlers(@NonNull final UtilPrngHandler prngHandler) {
        this.prngHandler = Objects.requireNonNull(prngHandler, "prngHandler must not be null");
    }

    public UtilPrngHandler prngHandler() {
        return prngHandler;
    }
}
