// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtual.merkle.reconnect;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import com.swirlds.common.merkle.synchronization.views.CustomReconnectRoot;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import com.swirlds.virtualmap.internal.merkle.VirtualRootNode;
import org.hiero.base.constructable.ConstructableIgnored;

/**
 * Imitates a VirtualRootNode, can be used to inject a custom view to be used during the reconnect.
 * Useful for forcing reconnect to fail at particular points in time.
 */
@ConstructableIgnored
public class FakeVirtualRootNode extends PartialBinaryMerkleInternal
        implements CustomReconnectRoot<Long, Long>, MerkleInternal {

    private final TeacherTreeView<Long> teacherTreeView;

    /**
     * Create an object that mimics a virtual map to inject a tree view into the reconnect process
     *
     * @param teacherTreeView
     * 		the teacher tree view to inject
     */
    public FakeVirtualRootNode(final TeacherTreeView<Long> teacherTreeView) {
        this.teacherTreeView = teacherTreeView;
    }

    @Override
    public long getClassId() {
        return VirtualRootNode.CLASS_ID;
    }

    @Override
    public int getVersion() {
        return VirtualRootNode.ClassVersion.CURRENT_VERSION;
    }

    @Override
    public FakeVirtualRootNode copy() {
        throw new UnsupportedOperationException();
    }

    @Override
    public TeacherTreeView<Long> buildTeacherView(final ReconnectConfig reconnectConfig) {
        return teacherTreeView;
    }

    @Override
    public LearnerTreeView<Long> buildLearnerView(
            final ReconnectConfig reconnectConfig, final ReconnectMapStats mapStats) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setupWithOriginalNode(final MerkleNode originalNode) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setupWithNoData() {
        throw new UnsupportedOperationException();
    }
}
