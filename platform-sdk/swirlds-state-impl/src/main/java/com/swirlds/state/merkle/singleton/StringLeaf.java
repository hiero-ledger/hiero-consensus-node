// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.singleton;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.common.utility.Labeled;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/** A leaf in the merkle tree that stores a string as its value. */
public class StringLeaf extends PartialMerkleLeaf implements Labeled, MerkleLeaf {
    private static final long CLASS_ID = 0x9C829FF3B2283L;
    public static final int CLASS_VERSION = 1;

    private String label = "";

    /** Zero-arg constructor. */
    public StringLeaf() {}

    public StringLeaf(@NonNull final String label) {
        setLabel(label);
    }

    /** Copy constructor. */
    private StringLeaf(@NonNull final StringLeaf that) {
        this.label = that.label;
    }

    /** {@inheritDoc} */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /** {@inheritDoc} */
    @Override
    public int getVersion() {
        return CLASS_VERSION;
    }

    /** {@inheritDoc} */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeNormalisedString(label);
    }

    /** {@inheritDoc} */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        label = in.readNormalisedString(MAX_LABEL_LENGTH);
    }

    /** {@inheritDoc} */
    @Override
    public StringLeaf copy() {
        return new StringLeaf(this);
    }

    /** {@inheritDoc} */
    @Override
    public String getLabel() {
        return label;
    }

    /** Set the label. */
    public void setLabel(@NonNull final String label) {
        if (label.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("Label " + label + " exceeds maximum length of " + MAX_LABEL_LENGTH);
        }
        this.label = label;
    }
}
