// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.model;

import com.hedera.pbj.runtime.io.buffer.Bytes;

/**
 * Immutable data item read from a MerkleDB data file for validation processing.
 *
 * @param type the MerkleDB data type
 * @param bytes the serialized data content
 * @param location the packed data location (file index + byte offset)
 */
public record DiskDataItem(Type type, Bytes bytes, long location) implements ValidationItem {

    /**
     * MerkleDB data file types used in the validation pipeline.
     */
    public enum Type {
        /** Path to Key/Value - contains {@code VirtualLeafBytes} */
        P2KV,
        /** Path to Hash - contains {@code VirtualHashRecord} */
        P2H,
        /** Key to Path - contains HDHM {@code Bucket} entries */
        K2P,
        /** Sentinel value signaling processor threads to terminate */
        TERMINATOR
    }

    /**
     * Creates a terminator item to signal processor thread shutdown.
     *
     * @return a poison pill item
     */
    public static DiskDataItem poisonPill() {
        return new DiskDataItem(Type.TERMINATOR, Bytes.EMPTY, -1L);
    }

    /**
     * Checks if this item signals thread termination.
     *
     * @return true if this is a poison pill
     */
    public boolean isPoisonPill() {
        return type == Type.TERMINATOR;
    }
}
