// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.address;

import com.swirlds.base.state.MutabilityException;
import com.swirlds.platform.system.address.internal.AddressBookIterator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.crypto.Hashable;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.node.NodeId;

/**
 * The Address of every known member of the swirld. The getters are public and the setters aren't, so it is read-only
 * for apps. When enableEventStreaming is set to be true, the memo field is required and should be unique.
 */
@Deprecated(forRemoval = true) // the removal of this class is ongoing
public class AddressBook implements Iterable<Address>, SelfSerializable, Hashable {

    /**
     * The index of a node ID that does not exist in the address book.
     */
    public static final int NOT_IN_ADDRESS_BOOK_INDEX = -1;

    public static final long CLASS_ID = 0x4ee5498ef623fbe0L;

    private static class ClassVersion {
        /**
         * In this version, the version was written as a long.
         */
        public static final int ORIGINAL = 0;

        public static final int UNDOCUMENTED = 1;
        /**
         * In this version, ad-hoc code was used to read and write the list of addresses.
         */
        public static final int AD_HOC_SERIALIZATION = 2;
        /**
         * In this version, AddressBook uses the serialization utilities to read and write the list of addresses.
         */
        public static final int UTILITY_SERIALIZATION = 3;
        /**
         * In this version, the round number and next node ID fields were added to this class.
         */
        public static final int ADDRESS_BOOK_STORE_SUPPORT = 4;
        /**
         * In this version, NodeIds are SelfSerializable.
         */
        public static final int SELF_SERIALIZABLE_NODE_ID = 5;
    }

    // FUTURE WORK: remove this restriction and use other strategies to make serialization safe
    /**
     * The maximum number of addresses that are supported.
     */
    public static final int MAX_ADDRESSES = 1024;

    /**
     * The round number that should be used when the round number is unknown.
     */
    public static final long UNKNOWN_ROUND = Long.MIN_VALUE;

    /**
     * The round when this address book was created.
     */
    private long round = UNKNOWN_ROUND;

    /**
     * DEPRECATED FIELD as of v0.56.0 It remains for compatibility with protobuf serialization.  Its value will always
     * be > the highest node ID of address in the address book, but constraints will no longer be checked when changing
     * address books.
     * <p>
     * The node ID of the next address that can be added must be greater than or equal to this value.
     * <p>
     * INVARIANT: that nextNodeId is greater than the node ids of all addresses in the address book.
     */
    private NodeId nextNodeId = NodeId.FIRST_NODE_ID;

    /**
     * Maps node IDs to the address for that node ID.
     */
    private final Map<NodeId, Address> addresses = new HashMap<>();

    /**
     * A map of public keys to node ID.
     */
    private final Map<String /* public key */, NodeId> publicKeyToId = new HashMap<>();

    /**
     * A map of node IDs to indices within the address book. A node's index is equal to its position in a list of all
     * nodes sorted by node ID (from least to greatest).
     */
    private final Map<NodeId, Integer /* index */> nodeIndices = new HashMap<>();

    /**
     * All node IDs in this map, ordered least to greatest.
     */
    private final List<NodeId> orderedNodeIds = new ArrayList<>();

    /**
     * the total weight of all members
     */
    private long totalWeight;

    /**
     * the number of addresses with non-zero weight
     */
    private int numberWithWeight;

    /**
     * The hash of this address book.
     */
    private Hash hash;

    /**
     * Create an empty address book.
     */
    public AddressBook() {
        this(new ArrayList<>());
    }

    /**
     * Copy constructor.
     */
    @SuppressWarnings("CopyConstructorMissesField")
    private AddressBook(@NonNull final AddressBook that) {
        Objects.requireNonNull(that, "AddressBook must not be null");

        for (final Address address : that) {
            this.addNewAddress(address);
        }

        this.round = that.round;
        // the next node id is deprecated, but the value is copied here for compatibility with protobuf serialization
        this.nextNodeId = that.nextNodeId;
    }

    /**
     * Create an address book initialized with the given list of addresses.
     *
     * @param addresses the addresses to start with
     */
    public AddressBook(@NonNull final List<Address> addresses) {
        Objects.requireNonNull(addresses, "addresses must not be null");
        addresses.forEach(this::add);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.SELF_SERIALIZABLE_NODE_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * Get the round number when this address book was constructed.
     *
     * @return the round when this address book was constructed, or {@link #UNKNOWN_ROUND} if the round is unknown or
     * this address book was not constructed during a regular round
     */
    public long getRound() {
        return round;
    }

    /**
     * Set the round number when this address book was constructed.
     *
     * @param round the round when this address book was constructed, or {@link #UNKNOWN_ROUND} if the round is unknown
     *              or this address book was not constructed during a regular round
     * @return this object
     */
    public AddressBook setRound(final long round) {
        this.round = round;
        return this;
    }

    /**
     * Get the number of addresses currently in the address book.
     *
     * @return the number of addresses
     */
    public int getSize() {
        return addresses.size();
    }

    /**
     * Check if this address book is empty.
     *
     * @return true if this address book contains no addresses
     */
    public boolean isEmpty() {
        return addresses.isEmpty();
    }

    /**
     * Get the number of addresses currently in the address book that have a weight greater than zero.
     *
     * @return the number of addresses with a weight greater than zero
     */
    public int getNumberWithWeight() {
        return numberWithWeight;
    }

    /**
     * Get the total weight of all members added together, where each member has nonnegative weight. This is zero if
     * there are no members.
     *
     * @return the total weight
     */
    public long getTotalWeight() {
        return totalWeight;
    }

    /**
     * Find the NodeId for the member whose address has the given public key. Returns null if it does not exist.
     *
     * @param publicKey the public key to look up
     * @return the NodeId of the member with that key, or null if it was not found
     */
    @Nullable
    public NodeId getNodeId(@NonNull final String publicKey) {
        Objects.requireNonNull(publicKey, "publicKey must not be null");
        return publicKeyToId.get(publicKey);
    }

    /**
     * Find the NodeId for the member at a given index within the address book.
     *
     * @param index the index within the address book
     * @return a NodeId
     */
    @NonNull
    public NodeId getNodeId(final int index) {
        if (index < 0 || index >= addresses.size()) {
            throw new NoSuchElementException("no address with index " + index + " exists");
        }

        return orderedNodeIds.get(index);
    }

    /**
     * Get the index within the address book of a given node ID.  Check that the addressbook {@link #contains(NodeId)}
     * the node ID to avoid throwing an exception.
     *
     * @param id the node's ID
     * @return the index of the node ID within the address book
     * @throws NoSuchElementException if the node ID does not exist in the address book.
     */
    public int getIndexOfNodeId(@NonNull final NodeId id) {
        Objects.requireNonNull(id, "nodeId is null");
        return nodeIndices.getOrDefault(id, NOT_IN_ADDRESS_BOOK_INDEX);
    }

    /**
     *  @return the next available node id for use in the address book.
     */
    public NodeId getNextAvailableNodeId() {
        return getSize() == 0 ? NodeId.FIRST_NODE_ID : getNodeId(getSize() - 1).getOffset(1);
    }

    /**
     * This method and its internal field are deprecated and no longer supported. The existence of this method and its
     * internal field remains for compatibility with protobuf serialization.  The returned value is either the last
     * value set by {@link #setNextNodeId(NodeId)} or the result of increasing the value to 1+ the node id of a new
     * address when adding a new address to the address book.  The comments below reflect the old usage
     * of the method.
     * <p>
     * Get the next available node ID.
     *
     * @return the next available node ID
     */
    @Deprecated(forRemoval = true, since = "0.56.0")
    @NonNull
    public NodeId getNextNodeId() {
        return nextNodeId;
    }

    /**
     * This method and its internal field are deprecated and no longer supported. The set value updates the internal
     * field, but is no longer checked against other data in the address book.  The existence of this method and its
     * internal field remains for compatibility with protobuf serialization.  The comments below reflect the old usage
     * of the method and are no longer accurate.
     *
     * <p>
     * Set the expected next node ID to be added to this address book.
     * </p>
     *
     * <p>
     * WARNING: the next node ID is typically maintained internally by the address book, and incorrect configuration may
     * lead to undefined behavior. This value should only be manually set if an address book is being constructed from
     * scratch for a round later than genesis (as opposed to constructing the address book iteratively by replaying all
     * address book transactions since genesis).
     * </p>
     *
     * @param newNextNodeId the next node ID for the address book
     * @return this object
     */
    @Deprecated(forRemoval = true, since = "0.56.0")
    @NonNull
    public AddressBook setNextNodeId(@NonNull final NodeId newNextNodeId) {
        this.nextNodeId = newNextNodeId;
        return this;
    }

    /**
     * Get the address for the member with the given ID.  Use {@link #contains(NodeId)} to check for its existence and
     * avoid an exception.
     *
     * @param id the member ID of the address to get
     * @return the address
     * @throws NoSuchElementException if no address with the given ID exists
     */
    @NonNull
    public Address getAddress(@NonNull final NodeId id) {
        Objects.requireNonNull(id, "NodeId is null");
        final Address address = addresses.get(id);
        if (address == null) {
            throw new NoSuchElementException("no address with id " + id + " exists");
        }
        return address;
    }

    /**
     * Check if an address for a given node ID is contained within this address book.
     *
     * @param id a node ID
     * @return true if this address book contains an address for the given node ID
     */
    public boolean contains(@Nullable final NodeId id) {
        return id != null && addresses.containsKey(id);
    }

    /**
     * The address book maintains a list of deterministically ordered node IDs. Add a new node ID to the end of that
     * list and record its index.
     *
     * @param nodeId the ID of the node being added
     */
    private void addToOrderedList(@NonNull final NodeId nodeId) {
        final int index = orderedNodeIds.size();
        orderedNodeIds.add(nodeId);
        nodeIndices.put(nodeId, index);
    }

    /**
     * The address book maintains a list of deterministically ordered node IDs. Remove a node ID from that list, remove
     * it from the index map, and update the indices of any node that had to be shifted as a result.
     *
     * @param nodeId the ID of the node being removed
     */
    private void removeNodeFromOrderedList(@NonNull final NodeId nodeId) {
        final int indexToRemove = nodeIndices.remove(nodeId);
        orderedNodeIds.remove(indexToRemove);

        for (int index = indexToRemove; index < orderedNodeIds.size(); index++) {
            nodeIndices.put(orderedNodeIds.get(index), index);
        }
    }

    /**
     * Updates the weight on the address with the given ID. If the address does not exist, a NoSuchElementException is
     * thrown. If the weight value is negative, an IllegalArgumentException is thrown.  If the address book is
     * immutable, a MutabilityException is thrown. This method does not validate the address book after updating the
     * address.  When the user is finished with making incremental changes, the final address book should be validated.
     *
     * @param id     the ID of the address to update.
     * @param weight the new weight value.  The weight must be nonnegative.
     * @throws NoSuchElementException   if the address does not exist.
     * @throws IllegalArgumentException if the weight is negative.
     * @throws MutabilityException      if the address book is immutable.
     */
    public void updateWeight(@NonNull final NodeId id, final long weight) {
        Objects.requireNonNull(id, "NodeId is null");
        final Address address = getAddress(id);
        if (weight < 0) {
            throw new IllegalArgumentException("weight must be nonnegative");
        }
        updateAddress(address.copySetWeight(weight));
    }

    /**
     * Update an existing entry in the address book.
     *
     * @param address the new address
     */
    private void updateAddress(@NonNull final Address address) {
        final Address oldAddress = Objects.requireNonNull(addresses.put(address.getNodeId(), address));

        publicKeyToId.remove(oldAddress.getNickname());
        publicKeyToId.put(address.getNickname(), address.getNodeId());

        final long oldWeight = oldAddress.getWeight();
        final long newWeight = address.getWeight();

        totalWeight -= oldWeight;
        totalWeight += newWeight;

        if (oldWeight == 0 && newWeight != 0) {
            numberWithWeight++;
        } else if (oldWeight != 0 && newWeight == 0) {
            numberWithWeight--;
        }

        addresses.put(address.getNodeId(), address);
    }

    /**
     * Add a new address at the end of the address book.
     *
     * @param address the address to add
     */
    private void addNewAddress(@NonNull final Address address) {
        final NodeId addressNodeId = address.getNodeId();
        final int addressBookSize = getSize();
        final NodeId nextAvailable = addressBookSize == 0
                ? NodeId.FIRST_NODE_ID
                : getNodeId(getSize() - 1).getOffset(1);
        final int nodeIdComparison = addressNodeId.compareTo(nextAvailable);
        if (nodeIdComparison < 0) {
            throw new IllegalArgumentException("Can not add address with node ID " + address.getNodeId()
                    + ", the next address to be added is required have a node ID greater or equal to "
                    + nextAvailable);
        }
        if (addresses.size() >= MAX_ADDRESSES) {
            throw new IllegalStateException("Address book is only permitted to hold " + MAX_ADDRESSES + " entries");
        }

        addresses.put(address.getNodeId(), address);
        publicKeyToId.put(address.getNickname(), address.getNodeId());
        addToOrderedList(address.getNodeId());

        totalWeight += address.getWeight();
        if (!address.isZeroWeight()) {
            numberWithWeight++;
        }
    }

    /**
     * Add an address to the address book, replacing the existing address with the same ID if one is present.
     *
     * @param address the address for that member, may not be null
     * @return this object
     */
    @NonNull
    public AddressBook add(@NonNull final Address address) {
        Objects.requireNonNull(address, "address must not be null");

        if (addresses.containsKey(address.getNodeId())) {
            // FUTURE WORK: adding an address here is a strange API pattern
            updateAddress(address);
        } else {
            addNewAddress(address);
        }

        return this;
    }

    /**
     * Remove an address associated with a given node ID.
     *
     * @param id the node ID that should have its address removed
     * @return this object
     */
    @NonNull
    public AddressBook remove(@NonNull final NodeId id) {
        Objects.requireNonNull(id, "NodeId is null");

        final Address address = addresses.remove(id);

        if (address == null) {
            return this;
        }

        publicKeyToId.remove(address.getNickname());
        removeNodeFromOrderedList(id);

        totalWeight -= address.getWeight();
        if (!address.isZeroWeight()) {
            numberWithWeight--;
        }
        orderedNodeIds.remove(id);

        return this;
    }

    /**
     * Remove all addresses from the address book.
     */
    public void clear() {
        addresses.clear();
        publicKeyToId.clear();
        nodeIndices.clear();
        orderedNodeIds.clear();

        totalWeight = 0;
        numberWithWeight = 0;
        nextNodeId = NodeId.FIRST_NODE_ID;
    }

    /**
     * Create a copy of this address book. The copy is always mutable, and the original maintains its original
     * mutability status.
     */
    // @Override
    @NonNull
    public AddressBook copy() {
        return new AddressBook(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(@NonNull final SerializableDataOutputStream out) throws IOException {
        Objects.requireNonNull(out, "out must not be null");
        out.writeSerializableIterableWithSize(iterator(), addresses.size(), false, true);
        out.writeLong(round);
        out.writeSerializable(nextNodeId, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(@NonNull final SerializableDataInputStream in, final int version) throws IOException {
        Objects.requireNonNull(in, "in must not be null");
        in.readSerializableIterableWithSize(MAX_ADDRESSES, false, Address::new, this::addNewAddress);

        round = in.readLong();
        if (version < ClassVersion.SELF_SERIALIZABLE_NODE_ID) {
            nextNodeId = NodeId.of(in.readLong());
        } else {
            nextNodeId = in.readSerializable(false, NodeId::new);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.ADDRESS_BOOK_STORE_SUPPORT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Iterator<Address> iterator() {
        return new AddressBookIterator(orderedNodeIds.iterator(), addresses);
    }

    /**
     * Get a set of all node IDs in the address book. Set is safe to modify.
     *
     * @return a set of all node IDs in the address book
     */
    @NonNull
    public Set<NodeId> getNodeIdSet() {
        return new HashSet<>(addresses.keySet());
    }

    /**
     * The text form of an address book that appears in config.txt
     *
     * @return the string form of the AddressBook that would appear in config.txt
     */
    @NonNull
    public String toConfigText() {
        return AddressBookUtils.addressBookConfigText(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final AddressBook that = (AddressBook) o;
        return Objects.equals(addresses, that.addresses) && getRound() == that.getRound();
        // The nextNodeId field has been removed from the equality check
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash getHash() {
        return hash;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setHash(final Hash hash) {
        this.hash = hash;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return addresses.hashCode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("AddressBook {\n");
        for (final Address address : this) {
            sb.append("   ").append(address).append(",\n");
        }
        sb.append("}");

        return sb.toString();
    }
}
