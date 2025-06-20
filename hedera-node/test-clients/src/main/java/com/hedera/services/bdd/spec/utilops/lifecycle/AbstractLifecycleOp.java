// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.lifecycle;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CompletableFuture;

/**
 * A convenient base class for dealing with node lifecycle operations.
 */
public abstract class AbstractLifecycleOp extends UtilOp {
    /**
     * The {@link com.hedera.services.bdd.junit.hedera.NodeSelector} to use to choose which node(s) to operate on
     */
    private final NodeSelector selector;

    protected AbstractLifecycleOp(@NonNull final NodeSelector selector) {
        this.selector = requireNonNull(selector);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        CompletableFuture.allOf(spec.targetNetworkOrThrow().nodesFor(selector).stream()
                        .map(node -> CompletableFuture.runAsync(() -> run(node, spec)))
                        .toArray(CompletableFuture[]::new))
                .join();
        // This operation has nothing more to do after all nodes have finished their run() methods
        return false;
    }

    protected abstract void run(@NonNull HederaNode node, @NonNull HapiSpec spec);
}
