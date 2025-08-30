// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.constructable.constructors;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.test.fixtures.state.TestMerkleStateRoot;

@Deprecated
@FunctionalInterface
public interface TestMerkleStateRootConstructor {
    TestMerkleStateRoot create(
            Configuration configuration, Metrics metrics, Time time, MerkleCryptography merkleCryptography);
}
