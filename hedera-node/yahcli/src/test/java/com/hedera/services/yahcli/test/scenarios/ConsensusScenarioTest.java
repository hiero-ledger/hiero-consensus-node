// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.scenarios;

import static com.hedera.services.bdd.spec.HapiPropertySource.asTopicString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.assertYahcliScenarios;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.deleteYahcliScenariosConfig;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliIvy;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;

/**
 * Validates that {@code yahcli ivy scenarios --consensus} creates a persistent topic when there is no config specifying
 * well-known entities; and then reuses that topic (but creates a novel topic) when run again with the
 * {@code --new-entities} flag.
 */
@Tag(REGRESSION)
@HapiTestLifecycle
@OrderedInIsolation
@TestInstance(PER_CLASS)
public class ConsensusScenarioTest {
    @Nullable
    private Long persistentTopicNum;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        // Ensure we start with no config
        lifecycle.doAdhoc(deleteYahcliScenariosConfig());
    }

    @HapiTest
    @Order(0)
    final Stream<DynamicTest> consensusScenarioCreatesLongLivedEntitiesWhenNoConfigYet() {
        return hapiTest(
                yahcliIvy("scenarios", "--consensus"),
                assertYahcliScenarios(s -> {
                    final var consensus = s.getConsensus();
                    assertNotNull(consensus);
                    persistentTopicNum = consensus.getPersistent();
                    assertNotNull(persistentTopicNum, "Persistent topic should be auto-created");
                }),
                sourcingContextual(spec -> blockingOrder(
                        getTopicInfo(asTopicString(spec.topicIdFactory().apply(requireNonNull(persistentTopicNum)))))));
    }

    @HapiTest
    @Order(1)
    final Stream<DynamicTest> consensusScenarioCreatesJustNovelEntitiesWithConfig() {
        return hapiTest(
                yahcliIvy("scenarios", "--consensus", "--new-entities"),
                assertYahcliScenarios(s -> {
                    final var consensus = s.getConsensus();
                    assertNotNull(consensus);
                    assertEquals(persistentTopicNum, consensus.getPersistent(), "Persistent topic should be reused");
                }),
                sourcingContextual(spec -> blockingOrder(
                        getTopicInfo(asTopicString(spec.topicIdFactory().apply(requireNonNull(persistentTopicNum)))),
                        getTopicInfo(asTopicString(spec.topicIdFactory().apply(requireNonNull(persistentTopicNum) + 1)))
                                .hasCostAnswerPrecheck(INVALID_TOPIC_ID))));
    }
}
