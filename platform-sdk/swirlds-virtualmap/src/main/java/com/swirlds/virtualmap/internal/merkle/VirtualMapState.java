// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.Path;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Contains state for a {@link VirtualMap}. This state is stored in memory. When an instance of {@link VirtualMap}
 * is serialized, it's stored as one of the key-value pairs.
 */
public class VirtualMapState {

    public static final int MAX_LABEL_CHARS = 512;

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
     * Create a new {@link VirtualMapState}.
     */
    public VirtualMapState(@NonNull final String label) {
        requireNonNull(label);
        firstLeafPath = -1;
        lastLeafPath = -1;
        this.label = label;
    }

    /**
     * Create a new {@link VirtualMapState}.
     */
    public VirtualMapState(@NonNull final String label, final long stateSize) {
        requireNonNull(label);
        if (stateSize == 0) {
            firstLeafPath = -1;
            lastLeafPath = -1;
        } else if (stateSize == 1) {
            firstLeafPath = 1;
            lastLeafPath = 1;
        } else {
            firstLeafPath = stateSize - 1;
            lastLeafPath = firstLeafPath + stateSize - 1;
        }
        this.label = label;
    }

    /**
     * Create a new {@link VirtualMapState} base on an {@link ExternalVirtualMapState} instance.
     * To be removed with ExternalVirtualMapState.
     *
     * @param virtualMapState The map state to copy. Cannot be null.
     */
    @Deprecated(forRemoval = true)
    public VirtualMapState(@NonNull final ExternalVirtualMapState virtualMapState) {
        requireNonNull(virtualMapState);
        firstLeafPath = virtualMapState.getFirstLeafPath();
        lastLeafPath = virtualMapState.getLastLeafPath();
        label = virtualMapState.getLabel();
    }

    /**
     * Copy constructor.
     */
    private VirtualMapState(final VirtualMapState virtualMapState) {
        firstLeafPath = virtualMapState.getFirstLeafPath();
        lastLeafPath = virtualMapState.getLastLeafPath();
        label = virtualMapState.getLabel();
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
     * @param path The new path. Can be {@link Path#INVALID_PATH}, or positive. Cannot be 0 or any other negative value.
     * @throws IllegalArgumentException If the path is not valid
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
     * @param path The new path. Can be {@link Path#INVALID_PATH}, or positive. Cannot be 0 or any other negative value.
     * @throws IllegalArgumentException If the path is not valid
     */
    public void setLastLeafPath(final long path) {
        if (path < 1 && path != Path.INVALID_PATH) {
            throw new IllegalArgumentException("The path must be positive, or INVALID_PATH, but was " + path);
        }
        if (path < firstLeafPath && path != Path.INVALID_PATH) {
            throw new IllegalArgumentException("The lastLeafPath must be greater than or equal to the firstLeafPath");
        }
        this.lastLeafPath = path;
    }

    /**
     * Gets the size of the virtual map. The size is defined as the number of leaves in the tree.
     * @return The size of the virtual map.
     */
    public long getSize() {
        if (firstLeafPath == -1) {
            return 0;
        }

        return lastLeafPath - firstLeafPath + 1;
    }

    /**
     * Gets the label for the virtual tree.
     *
     * @return The label.
     */
    public String getLabel() {
        return label;
    }

    /**
     * Sets the label for the virtual tree.  Needed to differentiate between different VirtualMaps.
     * @param label The new label. Cannot be null or empty. Cannot be longer than 512 characters.
     */
    public void setLabel(@NonNull final String label) {
        requireNonNull(label);
        if (label.length() > MAX_LABEL_CHARS) {
            throw new IllegalArgumentException("Label cannot be greater than 512 characters");
        }
        this.label = label;
    }

    public VirtualMapState copy() {
        return new VirtualMapState(this);
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
