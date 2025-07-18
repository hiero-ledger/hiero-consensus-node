// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualMap.ClassVersion;
import com.swirlds.virtualmap.internal.Path;
import java.io.IOException;
import java.util.Objects;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * Contains state for a {@link VirtualMap}. This state is stored in memory in the merkle tree as
 * the first (left) child of the VFCMap / {@link VirtualMap}.
 * This class exist only for backward compatibility with {@link VirtualMap} of versions prior to {@link ClassVersion#NO_VIRTUAL_ROOT_NODE}
 *
 * Use {@link VirtualMapMetadata} instead.
 */
public class ExternalVirtualMapMetadata extends PartialMerkleLeaf implements MerkleLeaf {
    public static final long CLASS_ID = 0x9e698c13a408250dL;
    private static final int CLASS_VERSION = 1;

    public static final int MAX_LABEL_CHARS = 512;
    public static final int MAX_LABEL_LENGTH = MAX_LABEL_CHARS * 3;

    /**
     * The path of the very first leaf in the tree. Can be -1 if there are no leaves.
     */
    private long firstLeafPath;

    /**
     * The path of the very last leaf in the tree. Can be -1 if there are no leaves.
     */
    private long lastLeafPath;

    /**
     * The label for the virtual tree.  Needed to differentiate between different VirtualMaps (for stats).
     */
    private String label;

    /**
     * Create a new {@link ExternalVirtualMapMetadata}.
     */
    public ExternalVirtualMapMetadata() {
        // Only use this constructor for serialization
        this((String) null);
    }

    /**
     * Create a new {@link ExternalVirtualMapMetadata}.
     */
    public ExternalVirtualMapMetadata(String label) {
        firstLeafPath = -1;
        lastLeafPath = -1;
        this.label = label;
    }

    /**
     * Create a copy of the {@link ExternalVirtualMapMetadata}.
     *
     * @param source
     * 		The map state to copy. Cannot be null.
     */
    private ExternalVirtualMapMetadata(final ExternalVirtualMapMetadata source) {
        this.firstLeafPath = source.firstLeafPath;
        this.lastLeafPath = source.lastLeafPath;
        this.label = source.label;
    }

    /**
     * Gets the firstLeafPath. Can be {@link Path#INVALID_PATH} if there are no leaves.
     *
     * @return The first leaf path.
     */
    public long getFirstLeafPath() {
        return firstLeafPath;
    }

    /**
     * Set the first leaf path.
     *
     * @param path
     * 		The new path. Can be {@link Path#INVALID_PATH}, or positive. Cannot be 0 or any other negative value.
     * @throws IllegalArgumentException
     * 		If the path is not valid
     */
    public void setFirstLeafPath(final long path) {
        if (path < 1 && path != Path.INVALID_PATH) {
            throw new IllegalArgumentException("The path must be positive, or INVALID_PATH, but was " + path);
        }
        if (path > lastLeafPath) {
            throw new IllegalArgumentException("The firstLeafPath must be less than or equal to the lastLeafPath");
        }
        firstLeafPath = path;
    }

    /**
     * Gets the lastLeafPath. Can be {@link Path#INVALID_PATH} if there are no leaves.
     *
     * @return The last leaf path.
     */
    public long getLastLeafPath() {
        return lastLeafPath;
    }

    /**
     * Set the last leaf path.
     *
     * @param path
     * 		The new path. Can be {@link Path#INVALID_PATH}, or positive. Cannot be 0 or any other negative value.
     * @throws IllegalArgumentException
     * 		If the path is not valid
     */
    public void setLastLeafPath(final long path) {
        if (path < 1 && path != Path.INVALID_PATH) {
            throw new IllegalArgumentException("The path must be positive, or INVALID_PATH, but was " + path);
        }
        if (path < firstLeafPath) {
            throw new IllegalArgumentException("The lastLeafPath must be greater than or equal to the firstLeafPath");
        }
        this.lastLeafPath = path;
    }

    // needs to be callable from VirtualMap.java, which is in the parent package.
    public long getSize() {
        if (firstLeafPath == -1) {
            return 0;
        }

        return lastLeafPath - firstLeafPath + 1;
    }

    // needs to be callable from VirtualMap.java, which is in the parent package.
    public String getLabel() {
        return label;
    }

    // needs to be callable from VirtualMap.java, which is in the parent package.
    public void setLabel(final String label) {
        Objects.requireNonNull(label);
        if (label.length() > MAX_LABEL_CHARS) {
            throw new IllegalArgumentException("Label cannot be greater than 512 characters");
        }
        this.label = label;
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
        return CLASS_VERSION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeLong(firstLeafPath);
        out.writeLong(lastLeafPath);

        out.writeNormalisedString(label);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        firstLeafPath = in.readLong();
        lastLeafPath = in.readLong();
        label = in.readNormalisedString(MAX_LABEL_LENGTH);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ExternalVirtualMapMetadata copy() {
        return new ExternalVirtualMapMetadata(this);
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("firstLeafPath", firstLeafPath)
                .append("lastLeafPath", lastLeafPath)
                .append("size", getSize())
                .append("label", label)
                .toString();
    }
}
