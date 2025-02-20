// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.sequence.map.internal;

import com.swirlds.base.utility.ToStringBuilder;
import java.util.HashSet;
import java.util.Set;

/**
 * A set of keys with a particular sequence number. This object is designed to be reused as the allowable
 * window of sequence numbers shifts.
 *
 * @param <K>
 * 		the type of the key
 */
public class SequenceKeySet<K> {

    private long sequenceNumber;
    private final Set<K> keys = new HashSet<>();

    /**
     * Create an object capable of holding keys for a sequence number.
     *
     * @param sequenceNumber
     * 		the initial sequence number to be stored in this object
     */
    public SequenceKeySet(final long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    /**
     * Get the sequence number currently stored in this object.
     *
     * @return the current sequence number
     */
    public long getSequenceNumber() {
        return sequenceNumber;
    }

    /**
     * Set the sequence number currently stored in this object.
     *
     * @param sequenceNumber
     * 		the sequence number stored in this object
     */
    public void setSequenceNumber(final long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    /**
     * Get the set of keys contained by this object.
     *
     * @return a set of keys
     */
    public Set<K> getKeys() {
        return keys;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("sequence number", sequenceNumber)
                .append("size", keys.size())
                .toString();
    }
}
