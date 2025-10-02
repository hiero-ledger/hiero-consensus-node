// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.dummy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import com.swirlds.common.merkle.synchronization.task.NodeToSend;
import com.swirlds.common.merkle.synchronization.views.CustomReconnectRoot;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedList;
import java.util.List;

/**
 * A test class for probing the behavior of self reconnecting nodes.
 */
public class DummyCustomReconnectRoot extends DummyMerkleInternal
        implements CustomReconnectRoot<NodeToSend, MerkleNode> {

    private static final long CLASS_ID = 0x3d03994ec6d42dccL;

    private final List<DummyTeacherPushMerkleTreeView> views;

    public DummyCustomReconnectRoot() {
        views = new LinkedList<>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasCustomReconnectView() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TeacherTreeView<NodeToSend> buildTeacherView(@NonNull final ReconnectConfig reconnectConfig) {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final DummyTeacherPushMerkleTreeView view =
                new DummyTeacherPushMerkleTreeView(platformContext.getConfiguration(), this);
        views.add(view);
        return view;
    }

    /**
     * Throw an exception if all views have not yet been closed.
     */
    public void assertViewsAreClosed() {
        for (final DummyTeacherPushMerkleTreeView view : views) {
            assertTrue(view.isClosed(), "view should have been closed");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setupWithOriginalNode(final MerkleNode originalNode) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void setupWithNoData() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public CustomReconnectRoot<NodeToSend, MerkleNode> createNewRoot() {
        return new DummyCustomReconnectRoot();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LearnerTreeView<MerkleNode> buildLearnerView(
            final ReconnectConfig reconnectConfig, @NonNull final ReconnectMapStats mapStats) {
        return new DummyLearnerPushMerkleTreeView(reconnectConfig, this, mapStats);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DummyCustomReconnectRoot copy() {
        throwIfImmutable();
        setImmutable(true);
        return new DummyCustomReconnectRoot();
    }

    @Override
    public String getValue() {
        return null;
    }
}
