// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure;

import com.hedera.services.bdd.spec.keys.KeyFactory;
import edu.umd.cs.findbugs.annotations.NonNull;

public interface SpecStateObserver {
    record SpecState(@NonNull HapiSpecRegistry registry, @NonNull KeyFactory keyFactory) {}

    void observe(@NonNull SpecState specState);
}
