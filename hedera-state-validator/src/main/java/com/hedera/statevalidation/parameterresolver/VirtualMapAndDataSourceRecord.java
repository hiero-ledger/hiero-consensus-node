// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.parameterresolver;

import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.virtualmap.VirtualMap;

public record VirtualMapAndDataSourceRecord(String name, MerkleDbDataSource dataSource, VirtualMap map) {
    @Override
    public String toString() {
        return name;
    }
}
