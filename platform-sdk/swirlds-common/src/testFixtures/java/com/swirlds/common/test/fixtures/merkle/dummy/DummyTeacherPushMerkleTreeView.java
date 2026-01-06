// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.dummy;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.views.TeacherPushMerkleTreeView;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * View for testing.
 */
public class DummyTeacherPushMerkleTreeView extends TeacherPushMerkleTreeView {

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Create a view for a standard merkle tree.
     *
     * @param reconnectConfig the reconnect configuration
     * @param root            the root of the tree
     */
    public DummyTeacherPushMerkleTreeView(@NonNull final ReconnectConfig reconnectConfig, final MerkleNode root) {
        super(reconnectConfig, root);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        assertFalse(closed.get(), "should only be closed once");
        closed.set(true);
    }

    /**
     * Check if this view has been closed.
     */
    public boolean isClosed() {
        return closed.get();
    }
}
