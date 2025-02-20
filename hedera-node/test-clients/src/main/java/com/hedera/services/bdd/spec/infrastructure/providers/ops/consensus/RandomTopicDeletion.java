// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.consensus;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOPIC_EXPIRED;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hedera.services.bdd.spec.transactions.consensus.HapiTopicDelete;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TopicID;
import java.util.Optional;

public class RandomTopicDeletion implements OpProvider {
    private final RegistrySourcedNameProvider<TopicID> topics;
    private final ResponseCodeEnum[] permissibleOutcomes = standardOutcomesAnd(TOPIC_EXPIRED, INVALID_TOPIC_ID);
    private final ResponseCodeEnum[] customOutcomes;

    public RandomTopicDeletion(RegistrySourcedNameProvider<TopicID> topics, ResponseCodeEnum[] customOutcomes) {
        this.topics = topics;
        this.customOutcomes = customOutcomes;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        final var topic = topics.getQualifying();
        if (topic.isEmpty()) {
            return Optional.empty();
        }
        HapiTopicDelete op = deleteTopic(topic.get())
                .hasKnownStatusFrom(plus(permissibleOutcomes, customOutcomes))
                .hasPrecheckFrom(plus(STANDARD_PERMISSIBLE_PRECHECKS, customOutcomes));
        return Optional.of(op);
    }
}
