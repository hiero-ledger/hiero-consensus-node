// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Represents a storage access, which could be either a read or a mutation.
 *
 * <p>The type of access (read or mutation) is decided by whether the {@link StorageAccess#writtenValue}
 * field is null. If null, this access was just a read. Otherwise, it was a write.
 *
 * @param key the key of the access
 * @param value the value read or overwritten
 * @param writtenValue if not null, the overwriting value
 */
public record StorageAccess(@NonNull UInt256 key, @NonNull UInt256 value, @Nullable UInt256 writtenValue) {
    public StorageAccess {
        requireNonNull(key, "Key cannot be null");
        requireNonNull(value, "Current value cannot be null");
    }

    /**
     * Returns the key as a {@link Bytes} object, trimmed of leading zeros.
     */
    public Bytes trimmedKeyBytes() {
        return tuweniToPbjBytes(key.toBytes().trimLeadingZeros());
    }

    /**
     * Returns the value as a {@link Bytes} object, trimmed of leading zeros.
     */
    public Bytes trimmedValueBytes() {
        return tuweniToPbjBytes(value.toBytes().trimLeadingZeros());
    }

    /**
     * Creates a new read access.
     *
     * @param key the key being read
     * @param value the value that was read
     * @return the access object representing this event
     */
    public static StorageAccess newRead(@NonNull UInt256 key, @NonNull UInt256 value) {
        return new StorageAccess(key, value, null);
    }

    /**
     * Creates a new write access.
     *
     * @param key the key being written
     * @param oldValue the old value being overwritten
     * @param newValue the new value being written
     * @return the access object representing this event
     */
    public static StorageAccess newWrite(@NonNull UInt256 key, @NonNull UInt256 oldValue, @NonNull UInt256 newValue) {
        return new StorageAccess(key, oldValue, requireNonNull(newValue));
    }

    /**
     * Returns true if this access replaced a non-zero storage value with a zero value.
     *
     * @return true if this access replaced a non-zero storage value with a zero value
     */
    public boolean isRemoval() {
        return writtenValue != null && writtenValue.isZero() && !value.isZero();
    }

    /**
     * Returns true if this access represents a logical change that would be externalized
     * in a legacy state changes sidecar.
     */
    public boolean isLogicalChange() {
        return isUpdate() && !isZeroIntoEmptySlot();
    }

    /**
     * Returns true if this access put a zero storage value into an empty slot.
     */
    public boolean isZeroIntoEmptySlot() {
        return writtenValue != null && writtenValue.isZero() && value.isZero();
    }

    /**
     * Returns true if this access replaced a zero storage value with a non-zero value.
     *
     * @return true if this access replaced a zero storage value with a non-zero value
     */
    public boolean isInsertion() {
        return writtenValue != null && !writtenValue.isZero() && value.isZero();
    }

    /**
     * Returns true if this access was a read.
     *
     * @return true if this access was a read
     */
    public boolean isReadOnly() {
        return writtenValue == null;
    }

    /**
     * Returns true if this access was an update.
     *
     * @return true if this access was an update
     */
    public boolean isUpdate() {
        return writtenValue != null;
    }

    public enum StorageAccessType {
        UNKNOWN,
        READ_ONLY,
        REMOVAL,
        INSERTION,
        UPDATE,
        ZERO_INTO_EMPTY_SLOT;

        public static StorageAccessType getAccessType(@NonNull final StorageAccess storageAccess) {
            requireNonNull(storageAccess);
            if (storageAccess.isReadOnly()) {
                return READ_ONLY;
            } else if (storageAccess.isRemoval()) {
                return REMOVAL;
            } else if (storageAccess.isInsertion()) {
                return INSERTION;
            } else if (storageAccess.isZeroIntoEmptySlot()) {
                return ZERO_INTO_EMPTY_SLOT;
            } else if (storageAccess.isUpdate()) {
                return UPDATE;
            }
            return UNKNOWN;
        }
    }
}
