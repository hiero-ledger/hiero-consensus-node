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
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Files;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
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

    @Nullable
    private final String text;

    @Nullable
    private final Pattern pattern;

    private final Duration delay;

    private final List<Map.Entry<Integer, AtomicReference<String>>> groupCaptures = new ArrayList<>();

    public LogContainmentOp(
            @NonNull final NodeSelector selector,
            @NonNull final ExternalPath path,
            @NonNull final Containment containment,
            @Nullable final String text,
            @Nullable final Pattern pattern,
            @NonNull final Duration delay) {
        if (path != ExternalPath.APPLICATION_LOG
                && path != ExternalPath.BLOCK_NODE_COMMS_LOG
                && path != ExternalPath.SWIRLDS_LOG) {
            throw new IllegalArgumentException(path + " is not a log");
        }
        if ((text == null && pattern == null) || (text != null && pattern != null)) {
            throw new IllegalArgumentException("Exactly one of text or pattern must be non-null");
        }
        this.path = requireNonNull(path);
        this.delay = requireNonNull(delay);
        this.text = text;
        this.pattern = pattern;
        this.selector = requireNonNull(selector);
        this.containment = requireNonNull(containment);
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
        if (pattern == null) {
            throw new IllegalStateException("exposingMatchGroupTo requires a pattern, not a text match");
        }
        groupCaptures.add(new AbstractMap.SimpleEntry<>(group, requireNonNull(ref)));
        return this;
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        doIfNotInterrupted(() -> MILLISECONDS.sleep(delay.toMillis()));
        final var searchTerm = text != null ? text : requireNonNull(pattern).pattern();
        // Per-group agreed captures, indexed to match groupCaptures list
        final String[] agreedCaptures = new String[groupCaptures.size()];
        for (final var node : spec.targetNetworkOrThrow().nodesFor(selector)) {
            final var logContents = rethrowIO(() -> Files.readString(node.getExternalPath(path)));
            final boolean isThere;
            if (text != null) {
                isThere = logContents.contains(text);
            } else {
                final var matcher = requireNonNull(pattern).matcher(logContents);
                isThere = matcher.find();
                if (isThere) {
                    for (int i = 0; i < groupCaptures.size(); i++) {
                        final var entry = groupCaptures.get(i);
                        final var nodeCapture = matcher.group(entry.getKey());
                        if (agreedCaptures[i] != null && !agreedCaptures[i].equals(nodeCapture)) {
                            Assertions.fail("Nodes disagree on captured group " + entry.getKey() + ": '"
                                    + agreedCaptures[i] + "' vs '" + nodeCapture + "'");
                        }
                        agreedCaptures[i] = nodeCapture;
                    }
                }
            }
            if (isThere && containment == Containment.DOES_NOT_CONTAIN) {
                Assertions.fail("Log for node '" + node.getName() + "' contains '" + searchTerm + "' and should not");
            } else if (!isThere && containment == Containment.CONTAINS) {
                Assertions.fail(
                        "Log for node '" + node.getName() + "' does not contain '" + searchTerm + "' but should");
            }
        }
        for (int i = 0; i < groupCaptures.size(); i++) {
            if (agreedCaptures[i] != null) {
                groupCaptures.get(i).getValue().set(agreedCaptures[i]);
            }
        }
        return false;
    }
}
