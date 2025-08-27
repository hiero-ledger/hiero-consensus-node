// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;
import static com.swirlds.virtualmap.internal.Path.getLeftChildPath;
import static com.swirlds.virtualmap.internal.Path.getRightChildPath;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.base.constructable.ConstructableIgnored;
import org.hiero.base.crypto.Hash;

/**
 * Represents a virtual internal merkle node.
 */
@ConstructableIgnored
public final class VirtualInternalNode extends PartialBinaryMerkleInternal implements MerkleInternal, VirtualNode {

    private static final int NUMBER_OF_CHILDREN = 2;

    public static final long CLASS_ID = 0xaf2482557cfdb6bfL;
    public static final int SERIALIZATION_VERSION = 1;

    /**
     * The {@link VirtualMap} associated with this node. Nodes cannot be moved from one map
     * to another.
     */
    private final VirtualMap map;

    /**
     * The {@link VirtualHashRecord} is the backing data for this node.
     */
    private final VirtualHashRecord virtualHashRecord;

    public VirtualInternalNode(@NonNull final VirtualMap map, @NonNull final VirtualHashRecord virtualHashRecord) {
        this.map = Objects.requireNonNull(map);
        this.virtualHashRecord = Objects.requireNonNull(virtualHashRecord);
        setHash(virtualHashRecord.hash());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfChildren() {
        return NUMBER_OF_CHILDREN;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T extends MerkleNode> T getChild(final int i) {
        final VirtualNode node;
        if (i == 0) {
            node = getLeft();
        } else if (i == 1) {
            node = getRight();
        } else {
            return null;
        }

        if (node == null) {
            return null;
        }

        final long targetPath = node.getPath();
        final List<Integer> routePath = Path.getRouteStepsFromRoot(targetPath);
        final MerkleRoute nodeRoute = this.map.getRoute().extendRoute(routePath);
        node.setRoute(nodeRoute);
        return (T) node;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setChild(final int index, final MerkleNode merkleNode) {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setChild(
            final int index, final MerkleNode merkleNode, final MerkleRoute merkleRoute, final boolean mayBeImmutable) {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void updateChildRoutes(final MerkleRoute route) {
        // Don't actually update child routes
    }

    /**
     * Always returns an ephemeral node, or one we already know about.
     */
    @SuppressWarnings("unchecked")
    @Override
    public VirtualNode getLeft() {
        return getChild(getLeftChildPath(virtualHashRecord.path()));
    }

    /**
     * Always returns an ephemeral node, or one we already know about.
     */
    @SuppressWarnings("unchecked")
    @Override
    public VirtualNode getRight() {
        return getChild(getRightChildPath(virtualHashRecord.path()));
    }

    private VirtualNode getChild(final long childPath) {
        if (childPath < map.getMetadata().getFirstLeafPath()) {
            return getInternalNode(childPath);
        } else {
            return getLeafNode(childPath);
        }
    }

    /**
     * Locates and returns an internal node based on the given path. A new instance
     * is returned each time.
     *
     * @param path
     * 		The path of the node to find. If INVALID_PATH, null is returned.
     * @return The node. Only returns null if INVALID_PATH was the path.
     */
    private VirtualInternalNode getInternalNode(final long path) {
        return getInternalNode(map, path);
    }

    /**
     * Returns an internal node for the given virtual path in the specified virtual map. If
     * the path is outside map's internal node range, {@code null} is returned.
     *
     * @param map Virtual map
     * @param path Virtual path
     * @return Virtual internal node
     */
    public static VirtualInternalNode getInternalNode(final VirtualMap map, final long path) {
        assert path != INVALID_PATH;

        // If the path is not a valid internal path then return null
        if (path >= map.getMetadata().getFirstLeafPath()) {
            return null;
        }

        final Hash hash = map.getRecords().findHash(path);
        // Only fully hashed virtual maps should be possible to iterate as merkle trees. In
        // this case, the hash above would never be null. However, some tests iterate over
        // virtual maps before they are hashed, and even before they become immutable. This
        // may result in null hashes
        final VirtualHashRecord rec = new VirtualHashRecord(path, hash);
        return new VirtualInternalNode(map, rec);
    }

    /**
     * @param path
     * 		The path. Must not be null and must be a valid path
     * @return The leaf, or null if there is not one.
     * @throws RuntimeException
     * 		If we fail to access the data store, then a catastrophic error occurred and
     * 		a RuntimeException is thrown.
     */
    private VirtualLeafNode getLeafNode(final long path) {
        return getLeafNode(map, path);
    }

    /**
     * Returns a leaf node for the given virtual path in the specified virtual map. If
     * the path is outside map's leaf path range, {@code null} is returned.
     *
     * @param map Virtual map
     * @param path Virtual path
     * @return Virtual leaf node
     */
    public static VirtualLeafNode getLeafNode(final VirtualMap map, final long path) {
        assert path != INVALID_PATH;
        assert path != ROOT_PATH;

        // If the path is not a valid leaf path then return null
        if ((path < map.getMetadata().getFirstLeafPath())
                || (path > map.getMetadata().getLastLeafPath())) {
            return null;
        }

        final Hash hash = map.getRecords().findHash(path);
        // Only fully hashed virtual maps should be possible to iterate as merkle trees. In
        // this case, the hash above would never be null. However, some tests iterate over
        // virtual maps before they are hashed, and even before they become immutable. This
        // may result in null hashes
        final VirtualLeafBytes<?> rec = map.getRecords().findLeafRecord(path);
        if (rec == null) {
            throw new IllegalStateException("Failed to find leaf node data: " + path);
        }
        return new VirtualLeafNode(rec, hash);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualInternalNode copy() {
        throw new UnsupportedOperationException("Don't use this. Need a map pointer.");
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
        return SERIALIZATION_VERSION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return new ToStringBuilder(this).append(virtualHashRecord).toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof final VirtualInternalNode that)) {
            return false;
        }

        return virtualHashRecord.equals(that.virtualHashRecord);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(virtualHashRecord);
    }

    @Override
    public long getPath() {
        return virtualHashRecord.path();
    }
}
