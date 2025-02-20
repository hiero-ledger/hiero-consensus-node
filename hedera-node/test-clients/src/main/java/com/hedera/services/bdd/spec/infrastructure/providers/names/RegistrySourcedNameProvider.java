// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.names;

import static java.util.Collections.EMPTY_SET;

import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.bdd.spec.infrastructure.RegistryChangeContext;
import com.hedera.services.bdd.spec.infrastructure.listeners.PresenceTrackingListener;
import com.hedera.services.bdd.suites.regression.factories.RegressionProviderFactory;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RegistrySourcedNameProvider<T> implements EntityNameProvider {
    static final Logger log = LogManager.getLogger(RegressionProviderFactory.class);

    private final Class<T> type;
    private final PresenceTrackingListener<T> listener;
    private final BiFunction<Set<String>, Set<String>, Optional<String>> selector;

    public RegistrySourcedNameProvider(
            Class<T> type, HapiSpecRegistry registry, BiFunction<Set<String>, Set<String>, Optional<String>> selector) {
        this.type = type;
        this.selector = selector;
        this.listener = new PresenceTrackingListener<>(type, registry);
    }

    public RegistrySourcedNameProvider(
            Class<T> type,
            HapiSpecRegistry registry,
            BiFunction<Set<String>, Set<String>, Optional<String>> selector,
            Predicate<RegistryChangeContext<T>> filter) {
        this.type = type;
        this.selector = selector;
        this.listener = new PresenceTrackingListener<>(type, registry, filter);
    }

    @Override
    public Optional<String> getQualifying() {
        return selector.apply(listener.allKnown(), EMPTY_SET);
    }

    @Override
    public Optional<String> getQualifyingExcept(Set<String> ineligible) {
        return selector.apply(listener.allKnown(), ineligible);
    }

    public int numPresent() {
        return listener.allKnown().size();
    }
}
