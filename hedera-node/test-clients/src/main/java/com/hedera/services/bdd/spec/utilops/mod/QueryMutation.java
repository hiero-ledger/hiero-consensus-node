// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.mod;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hederahashgraph.api.proto.java.Query;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

public interface QueryMutation extends BiFunction<Query, HapiSpec, Query> {
    /**
     * Constructs a {@link HapiSpec}-agnostic {@link QueryMutation} that simply applies
     * the given transform to the {@link Query}.
     *
     * @param operator the transform to apply to the query
     * @return the mutation for the transform
     */
    static QueryMutation withTransform(@NonNull final UnaryOperator<Query> operator) {
        return (query, spec) -> operator.apply(query);
    }
}
