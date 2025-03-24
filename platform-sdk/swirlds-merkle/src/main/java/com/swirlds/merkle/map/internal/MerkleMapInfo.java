// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.map.internal;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.common.utility.Labeled;
import java.io.IOException;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * Contains information about a {@link com.swirlds.merkle.map.MerkleMap MerkleMap}.
 */
public class MerkleMapInfo extends PartialMerkleLeaf implements Labeled, MerkleLeaf {

    private static final long CLASS_ID = 0x960210640cdcb44fL;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private String label = "";

    /**
     * Zero-arg constructor.
     */
    public MerkleMapInfo() {}

    /**
     * Copy constructor.
     */
    private MerkleMapInfo(final MerkleMapInfo that) {
        this.label = that.label;
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeNormalisedString(label);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        label = in.readNormalisedString(MAX_LABEL_LENGTH);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleMapInfo copy() {
        return new MerkleMapInfo(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getLabel() {
        return label;
    }

    /**
     * Set the label.
     */
    public void setLabel(final String label) {
        if (label.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("Label " + label + " exceeds maximum length of " + MAX_LABEL_LENGTH);
        }
        this.label = label;
    }
}
