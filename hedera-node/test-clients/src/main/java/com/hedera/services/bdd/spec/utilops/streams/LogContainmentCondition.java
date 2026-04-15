// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static com.swirlds.common.io.utility.FileUtils.rethrowIO;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Files;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;

/**
 * Shared log-matching support for log-based util ops.
 */
final class LogContainmentCondition {
    private final ExternalPath path;

    @Nullable
    private final String text;

    @Nullable
    private final Pattern pattern;

    private final List<Map.Entry<Integer, AtomicReference<String>>> groupCaptures = new ArrayList<>();

    LogContainmentCondition(
            @NonNull final ExternalPath path, @Nullable final String text, @Nullable final Pattern pattern) {
        validatePath(path);
        if ((text == null && pattern == null) || (text != null && pattern != null)) {
            throw new IllegalArgumentException("Exactly one of text or pattern must be non-null");
        }
        this.path = requireNonNull(path);
        this.text = text;
        this.pattern = pattern;
    }

    LogContainmentCondition exposingMatchGroupTo(final int group, @NonNull final AtomicReference<String> ref) {
        if (pattern == null) {
            throw new IllegalStateException("exposingMatchGroupTo requires a pattern, not a text match");
        }
        groupCaptures.add(new AbstractMap.SimpleEntry<>(group, requireNonNull(ref)));
        return this;
    }

    void assertContainment(
            @NonNull final List<HederaNode> nodes, @NonNull final LogContainmentOp.Containment containment) {
        final var agreedCaptures = new String[groupCaptures.size()];
        for (final var node : nodes) {
            final var result = matchFor(node);
            if (result.found()) {
                validateAgreedCaptures(agreedCaptures, result.captures());
            }
            if (result.found() && containment == LogContainmentOp.Containment.DOES_NOT_CONTAIN) {
                Assertions.fail("Log for node '" + node.getName() + "' contains '" + searchTerm() + "' and should not");
            } else if (!result.found() && containment == LogContainmentOp.Containment.CONTAINS) {
                Assertions.fail(
                        "Log for node '" + node.getName() + "' does not contain '" + searchTerm() + "' but should");
            }
        }
        if (containment == LogContainmentOp.Containment.CONTAINS) {
            publishCaptures(agreedCaptures);
        }
    }

    boolean isSatisfiedBy(@NonNull final List<HederaNode> nodes) {
        final var agreedCaptures = new String[groupCaptures.size()];
        for (final var node : nodes) {
            final var result = matchFor(node);
            if (!result.found()) {
                return false;
            }
            validateAgreedCaptures(agreedCaptures, result.captures());
        }
        publishCaptures(agreedCaptures);
        return true;
    }

    ExternalPath path() {
        return path;
    }

    String searchTerm() {
        return text != null ? text : requireNonNull(pattern).pattern();
    }

    private MatchResult matchFor(@NonNull final HederaNode node) {
        final var logContents = rethrowIO(() -> Files.readString(node.getExternalPath(path)));
        if (text != null) {
            return new MatchResult(logContents.contains(text), null);
        }
        final var matcher = requireNonNull(pattern).matcher(logContents);
        if (!matcher.find()) {
            return new MatchResult(false, null);
        }
        final var captures = new String[groupCaptures.size()];
        for (int i = 0; i < groupCaptures.size(); i++) {
            captures[i] = matcher.group(groupCaptures.get(i).getKey());
        }
        return new MatchResult(true, captures);
    }

    private void validateAgreedCaptures(@NonNull final String[] agreedCaptures, @Nullable final String[] nodeCaptures) {
        if (nodeCaptures == null) {
            return;
        }
        for (int i = 0; i < nodeCaptures.length; i++) {
            final var group = groupCaptures.get(i).getKey();
            final var nodeCapture = nodeCaptures[i];
            if (agreedCaptures[i] != null && !agreedCaptures[i].equals(nodeCapture)) {
                Assertions.fail("Nodes disagree on captured group " + group + ": '" + agreedCaptures[i] + "' vs '"
                        + nodeCapture + "'");
            }
            agreedCaptures[i] = nodeCapture;
        }
    }

    private void publishCaptures(@NonNull final String[] agreedCaptures) {
        for (int i = 0; i < agreedCaptures.length; i++) {
            if (agreedCaptures[i] != null) {
                groupCaptures.get(i).getValue().set(agreedCaptures[i]);
            }
        }
    }

    private static void validatePath(@NonNull final ExternalPath path) {
        if (path != ExternalPath.APPLICATION_LOG
                && path != ExternalPath.BLOCK_NODE_COMMS_LOG
                && path != ExternalPath.SWIRLDS_LOG) {
            throw new IllegalArgumentException(path + " is not a log");
        }
    }

    private record MatchResult(boolean found, @Nullable String[] captures) {}
}
