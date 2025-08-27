// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.stack.savepoints;

import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.PRECEDING;

import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.state.WrappedState;
import com.hedera.node.app.workflows.handle.stack.BuilderSink;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents the first save point of a child stack. When the save point is committed, the records are
 * flushed into either the preceding or following list of the parent sink based on the category of the
 * child dispatch. Therefore, this sink has a total capacity equal to either the parent's preceding
 * or following capacity.
 */
public class FirstChildSavepoint extends AbstractSavepoint {
    private final HandleContext.TransactionCategory txnCategory;

    /**
     * Constructs a {@link FirstChildSavepoint} instance.
     * @param state the current state
     * @param parentSink the parent sink
     * @param txnCategory the transaction category
     */
    public FirstChildSavepoint(
            @NonNull final WrappedState state,
            @NonNull final BuilderSink parentSink,
            @NonNull final HandleContext.TransactionCategory txnCategory) {
        super(
                state,
                parentSink,
                txnCategory == PRECEDING ? parentSink.precedingCapacity() : parentSink.followingCapacity());
        this.txnCategory = txnCategory;
    }

    @Override
    void commitBuilders() {
        if (txnCategory == PRECEDING) {
            flushPreceding(parent);
        } else {
            flushFollowing(parent);
        }
    }
}
