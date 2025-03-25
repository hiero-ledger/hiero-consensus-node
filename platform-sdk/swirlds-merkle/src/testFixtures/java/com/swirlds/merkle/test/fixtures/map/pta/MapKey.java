// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.pta;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.FastCopyable;
import java.io.IOException;
import java.util.Comparator;
import java.util.Objects;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class MapKey implements SelfSerializable, Comparable<MapKey>, FastCopyable {

    public static final long CLASS_ID = 0x63302b26cc321422L;

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    @JsonProperty
    private long shardId;

    @JsonProperty
    private long realmId;

    @JsonProperty
    private long accountId;

    private static final Comparator<MapKey> CANONICAL_ORDERING = Comparator.comparing(MapKey::getShardId)
            .thenComparingLong(MapKey::getRealmId)
            .thenComparingLong(MapKey::getAccountId);

    public MapKey(final long shardId, final long realmId, final long accountId) {
        this.shardId = shardId;
        this.realmId = realmId;
        this.accountId = accountId;
    }

    public MapKey() {}

    private MapKey(final MapKey mapKey) {
        this.shardId = mapKey.shardId;
        this.realmId = mapKey.realmId;
        this.accountId = mapKey.accountId;
    }

    public long getRealmId() {
        return realmId;
    }

    public long getShardId() {
        return shardId;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        final MapKey mapKey = (MapKey) other;
        return shardId == mapKey.shardId && realmId == mapKey.realmId && accountId == mapKey.accountId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(shardId, realmId, accountId);
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append(shardId)
                .append(realmId)
                .append(accountId)
                .toString();
    }

    @Override
    public int compareTo(final MapKey that) {
        return CANONICAL_ORDERING.compare(this, that);
    }

    public long getAccountId() {
        return accountId;
    }

    @Override
    public MapKey copy() {
        return new MapKey(this);
    }

    @Override
    public void serialize(SerializableDataOutputStream outStream) throws IOException {
        outStream.writeLong(this.shardId);
        outStream.writeLong(this.realmId);
        outStream.writeLong(this.accountId);
    }

    @Override
    public void deserialize(SerializableDataInputStream inputStream, int version) throws IOException {
        this.shardId = inputStream.readLong();
        this.realmId = inputStream.readLong();
        this.accountId = inputStream.readLong();
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }
}
