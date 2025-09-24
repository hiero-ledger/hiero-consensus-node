// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.constructable.constructors;

import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;

@FunctionalInterface
public interface MerkleDbDataSourceBuilderConstructor {

    MerkleDbDataSourceBuilder create(final Configuration configuration);
}
