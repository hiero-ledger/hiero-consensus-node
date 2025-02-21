// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.mod;

import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Encapsulates the description and expected response of a {@link BodyMutation}.
 *
 * @param summary a human-readable summary of the mutation
 * @param mutation the body mutation itself
 * @param expectedResponse the expected response after the mutation
 */
public record TxnModification(
        @NonNull String summary, @NonNull BodyMutation mutation, @NonNull ExpectedResponse expectedResponse) {

    public void customize(@NonNull final HapiTxnOp<?> op) {
        expectedResponse.customize(op.withBodyMutation(mutation));
    }
}
