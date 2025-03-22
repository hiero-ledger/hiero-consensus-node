// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.task;

import static com.swirlds.common.merkle.synchronization.task.LessonType.CUSTOM_VIEW_ROOT;
import static com.swirlds.common.merkle.synchronization.task.LessonType.INTERNAL_NODE_DATA;
import static com.swirlds.common.merkle.synchronization.task.LessonType.LEAF_NODE_DATA;
import static com.swirlds.common.merkle.synchronization.task.LessonType.NODE_IS_UP_TO_DATE;

import com.swirlds.common.Releasable;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import java.io.IOException;
import java.util.List;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * Used during the synchronization protocol to send data needed to reconstruct a single node.
 */
public class Lesson<T> implements Releasable, SelfSerializable {

    private static final long CLASS_ID = 0x98bc0d340d9bca1dL;

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private byte lessonType;
    private SelfSerializable subLesson;

    private LearnerTreeView<T> learnerView;

    /**
     * Zero-arg constructor for constructable registry.
     */
    public Lesson() {}

    /**
     * This constructor is used by the teacher to create new lessons.
     *
     * @param lessonType
     * 		the type of the lesson
     * @param subLesson
     * 		the payload of the lesson
     */
    public Lesson(final byte lessonType, final SelfSerializable subLesson) {
        this.lessonType = lessonType;
        this.subLesson = subLesson;
    }

    /**
     * This constructor is used by the learner when deserializing lessons.
     *
     * @param learnerTreeView
     * 		the learner's view
     */
    public Lesson(final LearnerTreeView<T> learnerTreeView) {
        this.learnerView = learnerTreeView;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeByte(lessonType);
        if (lessonType != NODE_IS_UP_TO_DATE) {
            subLesson.serialize(out);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        lessonType = in.readByte();

        switch (lessonType) {
            case NODE_IS_UP_TO_DATE:
                return;
            case LEAF_NODE_DATA:
                subLesson = new LeafDataLesson<>(learnerView);
                break;
            case INTERNAL_NODE_DATA:
                subLesson = new InternalDataLesson<>(learnerView);
                break;
            case CUSTOM_VIEW_ROOT:
                subLesson = new CustomViewRootLesson();
                break;
            default:
                throw new IllegalStateException("unsupported lesson type " + lessonType);
        }
        subLesson.deserialize(in, subLesson.getVersion());
    }

    /**
     * Returns true if the learner already has the required data.
     */
    public boolean isCurrentNodeUpToDate() {
        return lessonType == NODE_IS_UP_TO_DATE;
    }

    /**
     * Check if this lesson is about a leaf node.
     * @return true if the lesson is about a leaf node
     */
    public boolean isLeafLesson() {
        return lessonType == LEAF_NODE_DATA;
    }

    /**
     * Check if this lesson is about an internal node or a leaf node.
     * Unset if {@link #isCurrentNodeUpToDate()} returns true.
     *
     * @return if the node in the lesson is an internal node.
     */
    public boolean isInternalLesson() {
        return lessonType == INTERNAL_NODE_DATA;
    }

    /**
     * Check if there are any queries attached to this lesson.
     */
    public boolean hasQueries() {
        return lessonType == INTERNAL_NODE_DATA;
    }

    /**
     * Get the queries contained within this lesson (i.e. the hashes of the children of an internal node). Will be unset
     * and throw an exception if {@link #hasQueries()} returns false.
     *
     * @return a list of child hashes
     */
    @SuppressWarnings("unchecked")
    public List<Hash> getQueries() {
        return ((InternalDataLesson<T>) subLesson).getQueries();
    }

    /**
     * Get the leaf node. Will be unset and throw an exception if {@link #isCurrentNodeUpToDate()} is true or
     * if {@link #isCustomViewRoot()} is true.
     *
     * @return the leaf node, or null if unset
     */
    @SuppressWarnings("unchecked")
    public T getNode() {
        if (lessonType == LEAF_NODE_DATA) {
            return ((LeafDataLesson<T>) subLesson).getLeaf();
        }
        return ((InternalDataLesson<T>) subLesson).getInternal();
    }

    /**
     * Check if this is the initial lesson for a custom root.
     *
     * @return true if this is the initial lesson for the custom root
     */
    public boolean isCustomViewRoot() {
        return lessonType == CUSTOM_VIEW_ROOT;
    }

    /**
     * If {@link #isCustomViewRoot()} returns true, this method returns the class ID for the
     * root of the subtree with the custom view.
     *
     * @return the class ID of the root of the subtree
     */
    public long getCustomViewClassId() {
        return ((CustomViewRootLesson) subLesson).getRootClassId();
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    public boolean release() {
        if (lessonType == LEAF_NODE_DATA && learnerView != null) {
            final T node = ((LeafDataLesson<T>) subLesson).getLeaf();
            if (node != null) {
                learnerView.releaseNode(node);
            }
        }
        return true;
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
        return ClassVersion.ORIGINAL;
    }
}
