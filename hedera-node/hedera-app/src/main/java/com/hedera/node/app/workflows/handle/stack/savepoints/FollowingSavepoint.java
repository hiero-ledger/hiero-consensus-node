// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.stack.savepoints;

import com.hedera.node.app.state.WrappedState;
import com.hedera.node.app.workflows.handle.stack.Savepoint;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents any save point that is not first in the stack. When the save point is committed, the records are
 * flushed into the following list of the parent sink. So this sink has a total capacity equal to the parent's
 * following capacity.
 */
public class FollowingSavepoint extends AbstractSavepoint {

    public FollowingSavepoint(@NonNull WrappedState state, @NonNull Savepoint parent) {
        super(state, parent, parent.followingCapacity());
    }

    @Override
    void commitBuilders() {
        flushFollowing(parent);
    }
}
