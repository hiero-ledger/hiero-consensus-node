// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.task;

import java.io.IOException;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * This lesson describes a root of a subtree that requires a custom view to be used for reconnect.
 */
public class CustomViewRootLesson implements SelfSerializable {

    private static final long CLASS_ID = 0x1defd7fb7e8c303fL;

    private long rootClassId;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    /**
     * Zero arg constructor for constructable registry.
     */
    public CustomViewRootLesson() {}

    /**
     * Create a new custom view root lesson.
     *
     * @param rootClassId
     * 		the class ID of the node at teh root of the subtree
     */
    public CustomViewRootLesson(final long rootClassId) {
        this.rootClassId = rootClassId;
    }

    /**
     * Get the class ID of the node at the root of the subtree.
     */
    public long getRootClassId() {
        return rootClassId;
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
        out.writeLong(rootClassId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        rootClassId = in.readLong();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }
}
