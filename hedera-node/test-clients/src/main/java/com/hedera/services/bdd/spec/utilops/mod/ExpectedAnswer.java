// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.mod;

import static java.util.Arrays.asList;

import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ResponseType;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.EnumSet;
import java.util.Set;

/**
 * Encapsulates the expected answer to a query; if the {@link ResponseType#COST_ANSWER}
 * status is left null, it is assumed to be {@link ResponseCodeEnum#OK}.
 *
 * @param costAnswerStatus a failure status if the COST_ANSWER query should fail
 * @param answerOnlyStatus a failure status if just the ANSWER_ONLY query should fail
 */
public record ExpectedAnswer(
        @Nullable Set<ResponseCodeEnum> costAnswerStatus, @Nullable Set<ResponseCodeEnum> answerOnlyStatus) {
    public static ExpectedAnswer onCostAnswer(@NonNull ResponseCodeEnum... statuses) {
        return new ExpectedAnswer(EnumSet.copyOf(asList(statuses)), null);
    }

    public static ExpectedAnswer onAnswerOnly(@NonNull ResponseCodeEnum... statuses) {
        return new ExpectedAnswer(null, EnumSet.copyOf(asList(statuses)));
    }

    public void customize(@NonNull final HapiQueryOp<?> op) {
        if (costAnswerStatus != null) {
            op.hasCostAnswerPrecheckFrom(costAnswerStatus.toArray(ResponseCodeEnum[]::new));
        }
        if (answerOnlyStatus != null) {
            op.hasAnswerOnlyPrecheckFrom(answerOnlyStatus.toArray(ResponseCodeEnum[]::new));
        }
    }
}
