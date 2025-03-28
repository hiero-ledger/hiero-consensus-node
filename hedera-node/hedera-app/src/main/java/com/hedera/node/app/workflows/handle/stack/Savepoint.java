// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.stack;

import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior;
import com.hedera.node.config.types.StreamMode;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A transactional unit that buffers all changes to a wrapped Hedera state, along with any stream item builders
 * whose lifecycle is tied to that of the state changes. May be committed or rolled back.
 */
public interface Savepoint extends BuilderSink {
    /**
     * The state that this savepoint is buffering changes for, including all modifications made so far.
     *
     * @return the state
     */
    State state();

    /**
     * Rolls back all changes made in this savepoint, making any necessary changes to the stream item builders
     * this savepoint is managing. This method cannot be called twice and cannot be called after commit has been called.
     * <p>
     * <b>Important:</b> Unlike transactional management of state changes, which simply discards everything,
     * management of stream item builders includes flushing builders not of type {@link ReversingBehavior#REMOVABLE}
     * to the parent sink of the savepoint.
     * @throws IllegalStateException if the savepoint has already been committed or rolled back
     */
    void rollback();

    /**
     * Commits all changes made in this savepoint and flushes any stream item builders accumulated in this savepoint
     * to the parent sink of the savepoint.
     * This method cannot be called twice and cannot be called after rollback has been called.
     * @throws IllegalStateException if the savepoint has already been committed or rolled back
     */
    void commit();

    /**
     * Creates and returns a new stream item builder whose lifecycle will be scoped to the state changes made
     * in this savepoint.
     *
     * @param reversingBehavior the reversing behavior to apply to the builder on rollback
     * @param txnCategory the category of transaction initiating the new builder
     * @param customizer the customizer to apply when externalizing the builder
     * @param streamMode the mode of the stream
     * @param isBaseBuilder whether the builder is the base builder for a stack
     * @return the new builder
     */
    StreamBuilder createBuilder(
            @NonNull ReversingBehavior reversingBehavior,
            @NonNull HandleContext.TransactionCategory txnCategory,
            @NonNull StreamBuilder.TransactionCustomizer customizer,
            @NonNull StreamMode streamMode,
            boolean isBaseBuilder);

    /**
     * Tracks the given amount of fees collected into node accounts in this savepoint.
     * @param amount the amount to track
     */
    void trackCollectedNodeFee(long amount);

    /**
     * The total fees collected into node accounts and not rolled back in this savepoint.
     * @return the total fees collected
     */
    long getNodeFeesCollected();
}
