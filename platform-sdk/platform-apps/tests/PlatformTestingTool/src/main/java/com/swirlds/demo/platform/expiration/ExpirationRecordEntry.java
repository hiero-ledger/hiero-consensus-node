// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.expiration;

import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import java.util.Objects;

/**
 * Provides a process object useful for tracking the time of the
 * earliest-expiring record associated to an {@link MapKey}.
 */
public class ExpirationRecordEntry implements Comparable<ExpirationRecordEntry> {
    /**
     * earliest expiration time in a FCQ related to this MapKey
     */
    private final long earliestExpiry;

    private final MapKey mapKey;

    public ExpirationRecordEntry(long earliestExpiry, MapKey mapKey) {
        this.earliestExpiry = earliestExpiry;
        this.mapKey = mapKey;
    }

    public long getEarliestExpiry() {
        return earliestExpiry;
    }

    public MapKey getMapKey() {
        return mapKey;
    }

    @Override
    public int compareTo(ExpirationRecordEntry that) {
        return Long.compare(this.earliestExpiry, that.earliestExpiry);
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        final ExpirationRecordEntry that = (ExpirationRecordEntry) other;
        return earliestExpiry == that.earliestExpiry && Objects.equals(mapKey, that.mapKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(earliestExpiry, mapKey);
    }

    @Override
    public String toString() {
        return String.format("%s: id: %s; earliestExpiry: %d", "ExpirationRecordEntry", mapKey, earliestExpiry);
    }
}
