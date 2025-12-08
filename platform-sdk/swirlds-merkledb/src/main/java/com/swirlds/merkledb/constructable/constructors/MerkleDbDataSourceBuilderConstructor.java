// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.constructable.constructors;

import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;

/**
 * Should be removed after 0.70 release, once new snapshot format is deployed
 */
@Deprecated(forRemoval = true)
@FunctionalInterface
public interface MerkleDbDataSourceBuilderConstructor {

    MerkleDbDataSourceBuilder create(final Configuration configuration);
}
