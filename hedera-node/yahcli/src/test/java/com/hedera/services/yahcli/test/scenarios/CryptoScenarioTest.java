// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.scenarios;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.assertYahcliScenarios;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.deleteYahcliScenariosConfig;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliIvy;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
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
 * Validates that {@code yahcli ivy scenarios --crypto} creates a sender and receiver account when there is no config
 * specifying well-known accounts; and then reuses those accounts (but creates a novel account) when run again with
 * the {@code --new-entities} flag.
 */
@Tag(REGRESSION)
@HapiTestLifecycle
@OrderedInIsolation
@TestInstance(PER_CLASS)
public class CryptoScenarioTest {
    @Nullable
    private Long senderNum;

    @Nullable
    private Long receiverNum;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        // Ensure we start with no config
        lifecycle.doAdhoc(deleteYahcliScenariosConfig());
    }

    @HapiTest
    @Order(0)
    final Stream<DynamicTest> cryptoScenarioCreatesLongLivedEntitiesWhenNoConfigYet() {
        return hapiTest(
                yahcliIvy("scenarios", "--crypto"),
                assertYahcliScenarios(s -> {
                    final var crypto = s.getCrypto();
                    assertNotNull(crypto);
                    senderNum = crypto.getSender();
                    assertNotNull(senderNum, "Sender should be auto-created");
                    receiverNum = crypto.getReceiver();
                    assertNotNull(receiverNum, "Receiver should be auto-created");
                }),
                sourcingContextual(spec -> blockingOrder(
                        getAccountInfo(asAccountString(spec.accountIdFactory().apply(requireNonNull(senderNum)))),
                        getAccountInfo(asAccountString(spec.accountIdFactory().apply(requireNonNull(receiverNum)))))));
    }

    @HapiTest
    @Order(1)
    final Stream<DynamicTest> cryptoScenarioCreatesJustNovelEntitiesWithConfig() {
        return hapiTest(
                yahcliIvy("scenarios", "--crypto", "--new-entities"),
                assertYahcliScenarios(s -> {
                    final var crypto = s.getCrypto();
                    assertNotNull(crypto);
                    assertEquals(senderNum, crypto.getSender(), "Sender should be reused");
                    assertEquals(receiverNum, crypto.getReceiver(), "Receiver should be reused");
                }),
                sourcingContextual(spec -> blockingOrder(
                        getAccountInfo(asAccountString(spec.accountIdFactory().apply(requireNonNull(senderNum)))),
                        getAccountInfo(asAccountString(spec.accountIdFactory().apply(requireNonNull(receiverNum)))),
                        getAccountInfo(asAccountString(spec.accountIdFactory().apply(requireNonNull(receiverNum) + 1L)))
                                .hasCostAnswerPrecheck(ACCOUNT_DELETED))));
    }
}
