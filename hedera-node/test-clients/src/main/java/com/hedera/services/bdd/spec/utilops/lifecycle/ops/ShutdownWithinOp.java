// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.lifecycle.ops;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNode;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.lifecycle.AbstractLifecycleOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Shuts down the selected node or nodes specified by the {@link NodeSelector}.
 */
public class ShutdownWithinOp extends AbstractLifecycleOp {
    private static final Logger log = LogManager.getLogger(ShutdownWithinOp.class);

    private final Duration timeout;

    public ShutdownWithinOp(@NonNull final NodeSelector selector, @NonNull final Duration timeout) {
        super(selector);
        this.timeout = requireNonNull(timeout);
    }

    @Override
    protected void run(@NonNull final HederaNode node, @NonNull HapiSpec spec) {
        log.info("Waiting for '{}' to stop", node.getName());
        try {
            node.stopFuture().orTimeout(timeout.toMillis(), MILLISECONDS).join();
        } catch (CompletionException e) {
            // If we failed to dump threads from a subprocess node, its process is apparently no longer
            // running despite a failure to reap the exit status; so we continue the test in that case
            if (node.dumpThreads() || !(node instanceof SubProcessNode)) {
                throw new IllegalStateException("Failed to stop '" + node.getName() + "'", e);
            }
        }
        log.info("Stopped node '{}'", node.getName());
    }
}
