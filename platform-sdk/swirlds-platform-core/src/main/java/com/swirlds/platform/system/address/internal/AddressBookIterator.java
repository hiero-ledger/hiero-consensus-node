// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.address.internal;

import com.swirlds.platform.system.address.Address;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import org.hiero.consensus.model.node.NodeId;

/**
 * An iterator that walks over entries in an address book.
 */
public class AddressBookIterator implements Iterator<Address> {

    private final Iterator<NodeId> orderedNodeIds;
    private final Map<NodeId, Address> addresses;

    public AddressBookIterator(
            @NonNull final Iterator<NodeId> orderedNodeIds, @NonNull final Map<NodeId, Address> addresses) {
        this.orderedNodeIds = Objects.requireNonNull(orderedNodeIds, "the orderedNodeIds cannot be null");
        this.addresses = Objects.requireNonNull(addresses, "the addresses cannot be null");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() {
        return orderedNodeIds.hasNext();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Address next() {
        return Objects.requireNonNull(addresses.get(orderedNodeIds.next()));
    }
}
