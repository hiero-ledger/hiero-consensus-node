// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.task;

import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import java.io.IOException;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * This lesson describes a leaf node.
 *
 */
public class LeafDataLesson implements SelfSerializable {

    private static final long CLASS_ID = 0xafbdd5560579cb02L;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private TeacherTreeView teacherView;
    private LearnerTreeView learnerTreeView;
    private Long leaf;

    /**
     * Zero arg constructor for constructable registry.
     */
    public LeafDataLesson() {}

    /**
     * This constructor is used by the learner when deserializing.
     *
     * @param learnerTreeView
     * 		the view for the learner
     */
    public LeafDataLesson(final LearnerTreeView learnerTreeView) {
        this.learnerTreeView = learnerTreeView;
    }

    /**
     * Create a new lesson for a leaf node.
     *
     * @param teacherView
     * 		the view for the teacher
     * @param leaf
     * 		the leaf to send in the lesson
     */
    public LeafDataLesson(final TeacherTreeView teacherView, final Long leaf) {
        this.teacherView = teacherView;
        this.leaf = leaf;
    }

    /**
     * Get the leaf contained by this lesson.
     */
    public Long getLeaf() {
        return leaf;
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
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        teacherView.serializeLeaf(out, leaf);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        leaf = learnerTreeView.deserializeLeaf(in);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }
}
