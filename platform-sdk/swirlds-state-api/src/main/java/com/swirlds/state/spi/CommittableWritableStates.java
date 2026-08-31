// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

/**
 * A {@link WritableStates} implementation that is not buffering changes for a wrapped delegate, but itself knows how to
 * persist changes.
 */
public interface CommittableWritableStates {

    /**
     * Commits all changes.
     */
    void commit();

    /**
     * Commits only the named singleton. KV and queue buffers are left for a later
     * {@link #commit()} or {@code VirtualMap.copy()}.
     *
     * <p>Default falls back to {@link #commit()} for backends that cannot flush one state.
     *
     * @param stateId the singleton state id
     */
    default void commitSingleton(int stateId) {
        commit();
    }

    /**
     * Whether {@link #commit()} must run at the end of each user transaction.
     *
     * <p>Merkle backends can keep mutations in writable buffers until {@code VirtualMap.copy()}.
     * In-memory backends used by tests must commit immediately so readable views see the writes.
     *
     * @return {@code true} if wrap commit should call {@link #commit()}
     */
    default boolean requiresImmediateCommit() {
        return true;
    }
}
