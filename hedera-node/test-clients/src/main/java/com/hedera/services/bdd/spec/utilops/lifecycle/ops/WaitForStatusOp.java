// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.lifecycle.ops;

import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.awaitStatus;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.lifecycle.AbstractLifecycleOp;
import com.swirlds.platform.system.status.PlatformStatus;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Waits for the selected node or nodes specified by the {@link NodeSelector} to
 * reach the specified status within the given timeout.
 */
public class WaitForStatusOp extends AbstractLifecycleOp {
    private static final Logger log = LogManager.getLogger(WaitForStatusOp.class);

    private final Duration timeout;
    private final PlatformStatus status;

    public WaitForStatusOp(
            @NonNull NodeSelector selector, @NonNull final PlatformStatus status, @NonNull final Duration timeout) {
        super(selector);
        this.timeout = requireNonNull(timeout);
        this.status = requireNonNull(status);
    }

    @Override
    // assertion in production code
    @SuppressWarnings("java:S5960")
    public void run(@NonNull final HederaNode node, @NonNull HapiSpec spec) {
        awaitStatus(node, status, timeout);
    }

    @Override
    public String toString() {
        return "WaitFor" + status + "Within" + timeout;
    }
}
