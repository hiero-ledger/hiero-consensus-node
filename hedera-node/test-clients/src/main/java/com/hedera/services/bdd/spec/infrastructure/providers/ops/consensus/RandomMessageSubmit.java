// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.consensus;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CHUNK_NUMBER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CHUNK_TRANSACTION_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOPIC_EXPIRED;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.transactions.consensus.HapiMessageSubmit;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RandomMessageSubmit implements OpProvider {
    private static final Logger log = LogManager.getLogger(RandomMessageSubmit.class);

    public static final int DEFAULT_NUM_STABLE_TOPICS = 5;
    private final ResponseCodeEnum[] permissibleOutcomes =
            standardOutcomesAnd(TOPIC_EXPIRED, INVALID_TOPIC_ID, INVALID_CHUNK_NUMBER, INVALID_CHUNK_TRANSACTION_ID);

    private final SplittableRandom r = new SplittableRandom();
    private final EntityNameProvider topics;
    private int numStableTopics = DEFAULT_NUM_STABLE_TOPICS;
    private static byte[] messageBytes = new byte[1024];

    static {
        Arrays.fill(messageBytes, (byte) 0b1);
    }

    public RandomMessageSubmit(EntityNameProvider topics) {
        this.topics = topics;
    }

    public RandomMessageSubmit numStableTopics(int n) {
        numStableTopics = n;
        return this;
    }

    public static Set<String> stableTopics(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> String.format("stable-topic%d", i))
                .collect(toSet());
    }

    @Override
    public List<SpecOperation> suggestedInitializers() {
        return stableTopics(numStableTopics).stream()
                .map(topic -> createTopic(my(topic)).noLogging().deferStatusResolution())
                .collect(toList());
    }

    private String my(String opName) {
        return unique(opName, RandomMessageSubmit.class);
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        final var target = topics.getQualifying();
        if (target.isEmpty()) {
            return Optional.empty();
        }

        HapiMessageSubmit op = submitMessageTo(target.get())
                .message(new String(messageBytes))
                .hasKnownStatusFrom(permissibleOutcomes)
                .hasPrecheckFrom(STANDARD_PERMISSIBLE_PRECHECKS);

        return Optional.of(op);
    }
}
