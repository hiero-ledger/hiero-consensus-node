// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.mod;

import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import edu.umd.cs.findbugs.annotations.NonNull;

public record QueryModification(
        @NonNull String summary, @NonNull QueryMutation mutation, @NonNull ExpectedAnswer expectedAnswer) {

    public void customize(@NonNull final HapiQueryOp<?> op) {
        expectedAnswer.customize(op.withQueryMutation(mutation));
    }
}
