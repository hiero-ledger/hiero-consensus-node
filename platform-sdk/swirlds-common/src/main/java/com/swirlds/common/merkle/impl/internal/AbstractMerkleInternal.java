// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.impl.internal;

import static org.hiero.base.io.streams.SerializableStreamConstants.NULL_CLASS_ID;

import com.swirlds.base.state.MutabilityException;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.exceptions.IllegalChildTypeException;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;
import com.swirlds.common.merkle.impl.PartialNaryMerkleInternal;
import com.swirlds.common.merkle.interfaces.MerkleParent;
import com.swirlds.common.merkle.route.MerkleRoute;
import java.util.List;
import org.hiero.base.exceptions.ReferenceCountException;

/**
 * This abstract class implements boiler plate functionality for a binary {@link MerkleInternal} (i.e. an internal
 * node with 2 or fewer children). Classes that implement (@link MerkleInternal} are not required to extend an
 * abstract class such as this or {@link PartialNaryMerkleInternal}, but absent a reason it is recommended to do so
 * in order to avoid re-implementation of this code.
 */
public abstract sealed class AbstractMerkleInternal extends AbstractMerkleNode implements MerkleParent
        permits PartialBinaryMerkleInternal, PartialNaryMerkleInternal {

    /**
     * Constructor for AbstractMerkleInternal.
     */
    protected AbstractMerkleInternal() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isLeaf() {
        return false;
    }

    /**
     * Copy constructor. Initializes internal variables and copies the route. Does not copy children or other metadata.
     */
    protected AbstractMerkleInternal(final AbstractMerkleInternal that) {
        super(that);
    }

    /**
     * This is an implementation specific version of setChild.
     *
     * @param index
     * 		which child position is going to be updated
     * @param child
     * 		new node to attach
     */
    protected abstract void setChildInternal(final int index, final MerkleNode child);

    /**
     * Allow N-Ary and Binary Merkle classes to make space as appropriate.
     *
     * @param index
     * 		in the N-Ary case, expand the array to accommodate this many children
     * 		in the Binary case, this is a NOP
     */
    protected abstract void allocateSpaceForChild(final int index);

    /**
     * Check whether the requested index is in valid range [0, maximum child count).
     * In the Binary case, this is a NOP as the error testing happens in getChild(index).
     *
     * @param index
     * 		- child position to verify is legal
     */
    protected abstract void checkChildIndexIsValid(final int index);

    /**
     * {@inheritDoc}
     */
    @Override
    public void setChild(
            final int index, final MerkleNode child, final MerkleRoute childRoute, final boolean childMayBeImmutable) {

        throwIfInvalidState();

        checkChildIndexIsValid(index);

        throwIfInvalidChild(index, child, childMayBeImmutable);

        allocateSpaceForChild(index);

        final MerkleNode oldChild = getChild(index);
        if (oldChild == child) {
            return;
        }
        // When children change the hash needs to be invalidated.
        // Self hashing nodes are required to manage their own hash invalidation.
        if (!isSelfHashing()) {
            invalidateHash();
        }

        // Decrement the reference count of the original child
        if (oldChild != null) {
            oldChild.release();
        }

        if (child != null) {
            // Increment the reference count of the new child
            child.reserve();

            if (childRoute == null) {
                child.setRoute(computeRouteForChild(index));
            } else {
                child.setRoute(childRoute);
            }
        }

        setChildInternal(index, child);
    }

    /**
     * Check if a potential child is valid, and throw if it is not valid
     *
     * @param index
     * 		the index of the child
     * @param child
     * 		the child to be added
     * @param childMayBeImmutable
     * 		if true then the child is permitted to be immutable, if false then it is not permitted
     */
    private void throwIfInvalidChild(final int index, final MerkleNode child, final boolean childMayBeImmutable) {
        long classId = NULL_CLASS_ID;

        if (child != null) {
            classId = child.getClassId();

            if (!childMayBeImmutable && child.isImmutable()) {
                throw new MutabilityException("Immutable child can not be added to parent. parent = "
                        + this.getClass().getName() + ", child = "
                        + child.getClass().getName());
            }
        }

        if (!childHasExpectedType(index, classId)) {
            throw new IllegalChildTypeException(index, classId, this.getClass().getName());
        }
    }

    /**
     * Check if the given class ID is valid for a particular child.
     *
     * @param index
     * 		The child index.
     * @param childClassId
     * 		The class id of the child. May be NULL_CLASS_ID if the child is null.
     * @return true if the child has the appropriate type for the given version.
     */
    protected boolean childHasExpectedType(final int index, final long childClassId) {
        return true;
    }

    private void throwIfInvalidState() {
        if (isImmutable()) {
            throw new MutabilityException(
                    "Can not set child on immutable parent. " + this.getClass().getName());
        }
        if (isDestroyed()) {
            throw new ReferenceCountException(
                    "Can not set child on destroyed parent. " + this.getClass().getName());
        }
    }

    /**
     * Compute and construct a new route for a child at a given position.
     * Recycles the route of the existing child if possible.
     *
     * @param index
     * 		the index of the child
     */
    private MerkleRoute computeRouteForChild(final int index) {
        MerkleRoute childRoute = null;
        if (getNumberOfChildren() > index) {
            final MerkleNode oldChild = getChild(index);
            if (oldChild != null) {
                childRoute = oldChild.getRoute();
            }
        }
        if (childRoute == null) {
            childRoute = getRoute().extendRoute(index);
        }
        return childRoute;
    }

    /**
     * {@inheritDoc}
     *
     * Deserialize from a list of children
     *
     * @param children
     * 		A list of children.
     * @param version
     * 		Version (e.g. format) of the deserialized data.
     */
    @Override
    public void addDeserializedChildren(final List<MerkleNode> children, final int version) {
        for (int childIndex = 0; childIndex < children.size(); childIndex++) {
            setChild(childIndex, children.get(childIndex));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected final void onDestroy() {
        destroyNode();
        for (int index = 0; index < getNumberOfChildren(); index++) {
            final MerkleNode child = getChild(index);
            if (child != null) {
                child.release();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invalidateHash() {
        setHash(null);
    }

    /**
     * {@inheritDoc}
     *
     * WARNING: setting the route on an internal node with children requires a full iteration of the subtree.
     * For large trees this may be very expensive.
     */
    @Override
    public void setRoute(final MerkleRoute route) {
        if (!getRoute().equals(route)) {
            super.setRoute(route);
            updateChildRoutes(route);
        }
    }

    /**
     * If there are children, fix the routes of the children.
     *
     * @param route
     * 		this node's new route
     */
    protected void updateChildRoutes(final MerkleRoute route) {
        for (int index = 0; index < getNumberOfChildren(); index++) {
            final MerkleNode child = getChild(index);
            if (child != null) {
                child.setRoute(getRoute().extendRoute(index));
            }
        }
    }
}
