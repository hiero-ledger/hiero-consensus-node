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
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
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
        if (childPath < map.getState().getFirstLeafPath()) {
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
        assert path != INVALID_PATH : "Cannot happen. Path will be a child of virtual record path every time.";

        assert path < map.getState().getFirstLeafPath();
        Hash hash = map.getCache().lookupHashByPath(path);
        if (hash == null) {
            try {
                hash = map.getDataSource().loadHash(path);
            } catch (final IOException ex) {
                throw new UncheckedIOException("Failed to read a internal record from the data source", ex);
            }
        }

        final VirtualHashRecord rec = new VirtualHashRecord(path, hash != VirtualNodeCache.DELETED_HASH ? hash : null);
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
        // If the code was properly written, this will always hold true.
        assert path != INVALID_PATH;
        assert path != ROOT_PATH;

        // If the path is not a valid leaf path then return null
        if (path < map.getState().getFirstLeafPath() || path > map.getState().getLastLeafPath()) {
            return null;
        }

        // Check the cache first
        VirtualLeafBytes rec = map.getCache().lookupLeafByPath(path);

        // On cache miss, check the data source. It *has* to be there.
        if (rec == null) {
            try {
                rec = map.getDataSource().loadLeafRecord(path);
                // This should absolutely be impossible. We already checked to make sure the path falls
                // within the firstLeafPath and lastLeafPath, and we already failed to find the leaf
                // in the cache. It **MUST** be on disk, or we have a broken system.
                if (rec == null) {
                    throw new IllegalStateException("Attempted to read from disk but couldn't find the leaf");
                }
            } catch (final IOException ex) {
                throw new RuntimeException("Failed to read a leaf record from the data source", ex);
            }
        }

        Hash hash = map.getCache().lookupHashByPath(path);
        if (hash == null) {
            try {
                hash = map.getDataSource().loadHash(path);
            } catch (final IOException ex) {
                throw new UncheckedIOException("Failed to read a hash from the data source", ex);
            }
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
