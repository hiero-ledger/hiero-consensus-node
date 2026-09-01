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
     * <p>In-memory backends used by tests must {@link #commit()} immediately so readable views see
     * the writes and listeners fire. Merkle backends should {@link #flushToDataSource()} instead:
     * wrap apply already notified listeners, but working-state queries and ingest read the
     * {@code VirtualMap} and will miss the transaction unless the buffer is flushed.
     *
     * @return {@code true} if wrap commit should call {@link #commit()}
     */
    default boolean requiresImmediateCommit() {
        return true;
    }

    /**
     * Writes buffered mutations to the backing store without firing commit listeners.
     *
     * <p>The default calls {@link #commit()}. Merkle backends override this to flush into the
     * {@code VirtualMap} so a later {@code VirtualMap.copy()} is not required for readable views
     * of the same working state to observe the writes.
     */
    default void flushToDataSource() {
        commit();
    }
}
