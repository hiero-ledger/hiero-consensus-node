// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.lifecycle.ops;

import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.awaitStatus;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.lifecycle.AbstractLifecycleOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Arrays;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Waits for the selected node or nodes specified by the {@link NodeSelector} to
 * reach the specified status within the given timeout.
 */
public class WaitForStatusOp extends AbstractLifecycleOp {
    private final Duration timeout;
    private final PlatformStatus[] statuses;

    public WaitForStatusOp(
            @NonNull NodeSelector selector,
            @NonNull final Duration timeout,
            @NonNull final PlatformStatus... statuses) {
        super(selector);
        this.timeout = requireNonNull(timeout);
        this.statuses = requireNonNull(statuses);
    }

    @Override
    @SuppressWarnings("java:S5960")
    public void run(@NonNull final HederaNode node, @NonNull HapiSpec spec) {
        awaitStatus(node, timeout, statuses);
    }

    @Override
    public String toString() {
        return "WaitFor" + Arrays.toString(statuses) + "Within" + timeout;
    }
}
