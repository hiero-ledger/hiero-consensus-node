// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.doIfNotInterrupted;
import static com.swirlds.common.io.utility.FileUtils.rethrowIO;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Files;
import java.time.Duration;
import org.junit.jupiter.api.Assertions;

/**
 * A {@link UtilOp} that validates that the selected nodes' application or platform log contains or
 * does not contain a given pattern.
 */
public class LogContainmentOp extends UtilOp {
    public enum Containment {
        CONTAINS,
        DOES_NOT_CONTAIN
    }

    private final NodeSelector selector;
    private final ExternalPath path;
    private final Containment containment;
    private final String pattern;
    private final Duration delay;

    public LogContainmentOp(
            @NonNull final NodeSelector selector,
            @NonNull final ExternalPath path,
            @NonNull final Containment containment,
            @NonNull final String pattern,
            @NonNull final Duration delay) {
        if (path != ExternalPath.APPLICATION_LOG
                && path != ExternalPath.BLOCK_NODE_COMMS_LOG
                && path != ExternalPath.SWIRLDS_LOG) {
            throw new IllegalArgumentException(path + " is not a log");
        }
        this.path = requireNonNull(path);
        this.delay = requireNonNull(delay);
        this.pattern = requireNonNull(pattern);
        this.selector = requireNonNull(selector);
        this.containment = requireNonNull(containment);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        doIfNotInterrupted(() -> MILLISECONDS.sleep(delay.toMillis()));
        spec.targetNetworkOrThrow().nodesFor(selector).forEach(node -> {
            final var logContents = rethrowIO(() -> Files.readString(node.getExternalPath(path)));
            final var isThere = logContents.contains(pattern);
            if (isThere && containment == Containment.DOES_NOT_CONTAIN) {
                Assertions.fail("Log for node '" + node.getName() + "' contains '" + pattern + "' and should not");
            } else if (!isThere && containment == Containment.CONTAINS) {
                Assertions.fail("Log for node '" + node.getName() + "' does not contain '" + pattern + "' but should");
            }
        });
        return false;
    }
}
