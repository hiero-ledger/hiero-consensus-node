// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.mod;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * Defines a type able to mutate a {@link TransactionBody.Builder} in the
 * context of a {@link HapiSpec}.
 */
public interface BodyMutation extends BiFunction<TransactionBody.Builder, HapiSpec, TransactionBody.Builder> {
    /**
     * Constructs a {@link HapiSpec}-agnostic {@link BodyMutation} that simply applies
     * the given transform to the {@link TransactionBody}.
     *
     * @param operator the transform to apply to the body
     * @return the mutation for the transform
     */
    static BodyMutation withTransform(@NonNull final UnaryOperator<TransactionBody> operator) {
        return (builder, spec) -> operator.apply(builder.build()).toBuilder();
    }
}
