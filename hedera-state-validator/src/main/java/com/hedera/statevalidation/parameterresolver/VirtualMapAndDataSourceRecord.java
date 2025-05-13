// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.parameterresolver;

import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;

public record VirtualMapAndDataSourceRecord<K extends VirtualKey, V extends VirtualValue>(
        String name,
        MerkleDbDataSource dataSource,
        VirtualMap<K, V> map,
        KeySerializer<K> keySerializer,
        ValueSerializer<V> valueSerializer) {

    @Override
    public String toString() {
        return name;
    }
}
