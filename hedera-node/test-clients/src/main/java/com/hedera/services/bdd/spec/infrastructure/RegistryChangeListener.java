// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import java.util.Optional;

public interface RegistryChangeListener<T> {
    Class<T> forType();

    void onPut(String name, T value, Optional<HapiSpecOperation> cause);

    void onDelete(String name, Optional<HapiSpecOperation> cause);
}
