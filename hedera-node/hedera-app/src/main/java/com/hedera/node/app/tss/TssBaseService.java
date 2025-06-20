// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The service for the inexact weight TSS implementation to be used before completion of exact-weight TSS
 * scheme. This service is now responsible only for registering schemas to deserialize, and then remove,
 * the states added in {@code 0.56.0} and {@code 0.58.0}.
 */
@Deprecated(forRemoval = true, since = "0.59.0")
public interface TssBaseService extends Service {
    String NAME = "TssBaseService";

    @NonNull
    @Override
    default String getServiceName() {
        return NAME;
    }
}
