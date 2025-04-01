// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.stack.savepoints;

import com.hedera.node.app.state.WrappedState;
import com.hedera.node.app.workflows.handle.stack.BuilderSink;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents the first save point of a root stack created to handle a user dispatch.
 * When the save point is committed, the preceding builders are flushed into the parent's preceding list,
 * and the following builders are flushed into the parent's following list.
 * Therefore, this sink has total capacity of both the preceding and following capacity of the
 * parent sink although the capacity is limited in both directions.
 */
public class FirstRootSavepoint extends AbstractSavepoint {
    public FirstRootSavepoint(@NonNull final WrappedState state, @NonNull final BuilderSink parentSink) {
        super(state, parentSink, parentSink.precedingCapacity(), parentSink.followingCapacity());
    }

    @Override
    void commitBuilders() {
        flushInOrder(parent);
    }
}
