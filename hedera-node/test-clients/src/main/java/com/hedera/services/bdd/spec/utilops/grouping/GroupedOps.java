// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.grouping;

import com.hedera.services.bdd.spec.SpecOperation;

public interface GroupedOps<T extends GroupedOps<T>> extends SpecOperation {
    T failOnErrors();
}
