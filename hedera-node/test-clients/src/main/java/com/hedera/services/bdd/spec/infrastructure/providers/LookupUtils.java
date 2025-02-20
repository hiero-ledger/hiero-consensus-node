// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers;

import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class LookupUtils {
    public static Optional<Map.Entry<String, String>> twoDistinct(EntityNameProvider provider) {
        Optional<String> first = provider.getQualifying();
        if (first.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> second = provider.getQualifyingExcept(Set.of(first.get()));
        if (second.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AbstractMap.SimpleEntry<>(first.get(), second.get()));
    }
}
