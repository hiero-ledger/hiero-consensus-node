// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import static com.swirlds.virtualmap.datasource.VirtualDataSource.INVALID_PATH;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.Path;
import java.util.Objects;

/**
 * Contains state for a {@link VirtualMap}. This state is stored in memory. When an instance of {@link VirtualMap}
 * is serialized, it's stored as one of the key-value pairs.
 */
public class VirtualMapMetadata {

    /**
     * The path of the very first leaf in the tree. Can be -1 if there are no leaves.
     */
    private long firstLeafPath;

    /**
     * The path of the very last leaf in the tree. Can be -1 if there are no leaves.
     */
    private long lastLeafPath;

    /**
     * Create a new {@link VirtualMapMetadata}.
     */
    public VirtualMapMetadata() {
        firstLeafPath = INVALID_PATH;
        lastLeafPath = INVALID_PATH;
    }

    /**
     * Create a new {@link VirtualMapMetadata}.
     */
    public VirtualMapMetadata(final long firstLeafPath, final long lastLeafPath) {
        this.firstLeafPath = firstLeafPath;
        this.lastLeafPath = lastLeafPath;
    }

    /**
     * Create a new {@link VirtualMapMetadata}.
     */
    public VirtualMapMetadata(final long stateSize) {
        if (stateSize == 0) {
            firstLeafPath = INVALID_PATH;
            lastLeafPath = INVALID_PATH;
        }
        if (stateSize == 1) {
            firstLeafPath = 1;
            lastLeafPath = 1;
        } else {
            firstLeafPath = stateSize - 1;
            lastLeafPath = firstLeafPath + stateSize - 1;
        }
    }

    /**
     * Copy constructor.
     */
    private VirtualMapMetadata(final VirtualMapMetadata virtualMapMetadata) {
        firstLeafPath = virtualMapMetadata.getFirstLeafPath();
        lastLeafPath = virtualMapMetadata.getLastLeafPath();
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

    public VirtualMapMetadata copy() {
        return new VirtualMapMetadata(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("firstLeafPath", firstLeafPath)
                .append("lastLeafPath", lastLeafPath)
                .append("size", getSize())
                .toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        VirtualMapMetadata that = (VirtualMapMetadata) o;
        return firstLeafPath == that.firstLeafPath && lastLeafPath == that.lastLeafPath;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(firstLeafPath, lastLeafPath);
    }
}
