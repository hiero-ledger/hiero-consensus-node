// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.consensus;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOPIC_EXPIRED;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;

public class RandomTopicInfo implements OpProvider {
    private final ResponseCodeEnum[] permissibleCostAnswerPrechecks =
            standardQueryPrechecksAnd(TOPIC_EXPIRED, INVALID_TOPIC_ID);

    private final ResponseCodeEnum[] permissibleAnswerOnlyPrechecks =
            standardQueryPrechecksAnd(TOPIC_EXPIRED, INVALID_TOPIC_ID);

    private final EntityNameProvider topics;

    public RandomTopicInfo(EntityNameProvider topics) {
        this.topics = topics;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        final var target = topics.getQualifying();
        if (target.isEmpty()) {
            return Optional.empty();
        }

        var op = QueryVerbs.getTopicInfo(target.get())
                .hasCostAnswerPrecheckFrom(permissibleCostAnswerPrechecks)
                .hasAnswerOnlyPrecheckFrom(permissibleAnswerOnlyPrechecks);

        return Optional.of(op);
    }
}
