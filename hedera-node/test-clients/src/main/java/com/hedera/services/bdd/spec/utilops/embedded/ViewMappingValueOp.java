/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.bdd.spec.utilops.embedded;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

public class ViewMappingValueOp<K, V> extends UtilOp {

    private final String serviceName;
    private final String stateKey;
    private final K key;
    private final Consumer<V> observer;

    public ViewMappingValueOp(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final K key,
            @NonNull final Consumer<V> observer) {
        this.serviceName = requireNonNull(serviceName);
        this.stateKey = requireNonNull(stateKey);
        this.key = requireNonNull(key);
        this.observer = requireNonNull(observer);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var state = spec.embeddedStateOrThrow();
        final var readableStates = state.getReadableStates(serviceName);
        final var mapping = requireNonNull(readableStates.<K, V>get(stateKey));
        final var value = mapping.get(key);
        assertNotNull(value, "No value found for key '" + key + "' in state '" + serviceName + "." + stateKey + "'");
        observer.accept(value);
        return false;
    }
}
