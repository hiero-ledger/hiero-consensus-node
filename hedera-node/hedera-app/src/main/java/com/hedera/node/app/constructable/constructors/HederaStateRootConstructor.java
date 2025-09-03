// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.constructable.constructors;

import com.hedera.node.app.HederaStateRoot;
import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;

@Deprecated
@FunctionalInterface
public interface HederaStateRootConstructor {
    HederaStateRoot create(
            Configuration configuration, Metrics metrics, Time time, MerkleCryptography merkleCryptography);
}
