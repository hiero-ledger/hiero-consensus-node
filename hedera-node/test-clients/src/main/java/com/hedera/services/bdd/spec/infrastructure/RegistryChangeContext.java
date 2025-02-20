// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import java.util.Optional;

public class RegistryChangeContext<T> {
    private final T value;
    private final HapiSpecRegistry registry;
    private final Optional<HapiSpecOperation> cause;

    public RegistryChangeContext(T value, HapiSpecRegistry registry, Optional<HapiSpecOperation> cause) {
        this.value = value;
        this.registry = registry;
        this.cause = cause;
    }

    public T getValue() {
        return value;
    }

    public HapiSpecRegistry getRegistry() {
        return registry;
    }

    public Optional<HapiSpecOperation> getCause() {
        return cause;
    }
}
