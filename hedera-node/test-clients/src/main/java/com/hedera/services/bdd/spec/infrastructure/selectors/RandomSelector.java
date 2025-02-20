// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.selectors;

import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RandomSelector implements BiFunction<Set<String>, Set<String>, Optional<String>> {
    private static final Logger log = LogManager.getLogger(RandomSelector.class);

    SplittableRandom r = new SplittableRandom();

    private final Predicate<String> eligibility;

    public RandomSelector() {
        this(ignore -> true);
    }

    public RandomSelector(Predicate<String> eligibility) {
        this.eligibility = eligibility;
    }

    @Override
    public synchronized Optional<String> apply(Set<String> candidates, Set<String> ineligible) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        int n = r.nextInt(candidates.size());
        String candidate = null;
        try {
            Iterator<String> iterator = candidates.iterator();
            for (int i = 0; (i <= n) || (candidate == null); i++) {
                if (!iterator.hasNext()) {
                    break;
                }
                String nextCandidate = iterator.next();
                if (!ineligible.contains(nextCandidate) && eligibility.test(nextCandidate)) {
                    candidate = nextCandidate;
                }
            }
        } catch (Exception ignore) {
        }

        if (candidate == null || ineligible.contains(candidate) || !eligibility.test(candidate)) {
            return Optional.empty();
        }

        return Optional.of(candidate);
    }
}
