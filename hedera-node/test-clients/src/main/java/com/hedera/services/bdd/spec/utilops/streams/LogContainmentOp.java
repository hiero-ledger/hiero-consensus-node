// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.doIfNotInterrupted;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

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
    private final Containment containment;
    private final LogContainmentCondition condition;
    private final Duration delay;

    public LogContainmentOp(
            @NonNull final NodeSelector selector,
            @NonNull final ExternalPath path,
            @NonNull final Containment containment,
            @Nullable final String text,
            @Nullable final Pattern pattern,
            @NonNull final Duration delay) {
        this.delay = requireNonNull(delay);
        this.selector = requireNonNull(selector);
        this.containment = requireNonNull(containment);
        this.condition = new LogContainmentCondition(path, text, pattern);
    }

    /**
     * When using a pattern match, captures the given group from the matched line on each node and
     * asserts all nodes agree on the same value, then exposes it via {@code ref}. May be called
     * multiple times to capture different groups.
     *
     * @param group the capture group index (1-based)
     * @param ref the reference to populate with the captured value
     * @return {@code this}
     */
    public LogContainmentOp exposingMatchGroupTo(final int group, @NonNull final AtomicReference<String> ref) {
        condition.exposingMatchGroupTo(group, ref);
        return this;
    }

    /**
     * When using a pattern match, selects the last match in the log rather than the first.
     * Useful when the same log line can repeat across multiple network lifecycles and
     * only the most recent occurrence's capture groups are of interest.
     *
     * @return {@code this}
     */
    public LogContainmentOp matchingLast() {
        condition.matchLast();
        return this;
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        doIfNotInterrupted(() -> MILLISECONDS.sleep(delay.toMillis()));
        condition.assertContainment(spec.targetNetworkOrThrow().nodesFor(selector), containment);
        return false;
    }
}
