// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * A listener that can defer committing changes to the state with which they are associated.
 */
public interface DeferringListener {
    /**
     * Whether the state with which this listener is associated should defer committing changes.
     */
    default boolean deferCommits() {
        return false;
    }

    /**
     * Called when a commit is deferred.
     */
    default void commitDeferred() {}

    /**
     * Returns the agreed defer commit setting for the given listeners. If the listeners have
     * inconsistent defer commit settings, an exception is thrown.
     * @param listeners the list of listeners to check
     * @return true if all listeners agree to defer commits, false otherwise
     * @throws IllegalArgumentException if the listeners have inconsistent defer commit settings
     */
    static <T extends DeferringListener> boolean agreedDeferCommitOrThrow(@NonNull final List<T> listeners) {
        requireNonNull(listeners);
        boolean deferCommits = false;
        if (!listeners.isEmpty()) {
            deferCommits = listeners.getFirst().deferCommits();
            if (listeners.size() > 1) {
                final var restAgree = listeners.subList(1, listeners.size()).stream()
                        .allMatch(l -> l.deferCommits() == listeners.getFirst().deferCommits());
                if (!restAgree) {
                    throw new IllegalArgumentException("Listeners have inconsistent defer commit settings");
                }
            }
        }
        return deferCommits;
    }
}
