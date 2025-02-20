// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure;

import java.util.Optional;
import java.util.Set;

public interface EntityNameProvider {
    Optional<String> getQualifying();

    Optional<String> getQualifyingExcept(Set<String> ineligible);
}
