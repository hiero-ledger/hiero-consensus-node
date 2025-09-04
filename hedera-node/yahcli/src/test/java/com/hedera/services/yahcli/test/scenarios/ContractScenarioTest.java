// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.scenarios;

import static com.hedera.services.bdd.spec.HapiPropertySource.asContractString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.assertYahcliScenarios;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.deleteYahcliScenariosConfig;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliIvy;
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
 * Validates that {@code yahcli ivy scenarios --contract} creates a persistent contract when there is no config
 * specifying well-known entities; and then reuses that contract (but creates a novel contract) when run again with the
 * {@code --new-entities} flag.
 */
@Tag(REGRESSION)
@HapiTestLifecycle
@OrderedInIsolation
@TestInstance(PER_CLASS)
public class ContractScenarioTest {
    @Nullable
    private Long persistentContractNum;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        // Ensure we start with no config
        lifecycle.doAdhoc(deleteYahcliScenariosConfig());
    }

    @HapiTest
    @Order(0)
    final Stream<DynamicTest> contractScenarioCreatesLongLivedEntitiesWhenNoConfigYet() {
        return hapiTest(
                yahcliIvy("scenarios", "--contract"),
                assertYahcliScenarios(s -> {
                    final var contract = s.getContract();
                    assertNotNull(contract);
                    final var persistentContract = contract.getPersistent();
                    assertNotNull(persistentContract);
                    persistentContractNum = persistentContract.getNum();
                    assertNotNull(persistentContractNum, "Persistent contract should be auto-created");
                }),
                sourcingContextual(spec -> blockingOrder(getContractInfo(
                        asContractString(spec.contractIdFactory().apply(requireNonNull(persistentContractNum)))))));
    }

    @HapiTest
    @Order(1)
    final Stream<DynamicTest> contractScenarioCreatesJustNovelEntitiesWithConfig() {
        return hapiTest(
                yahcliIvy("scenarios", "--contract", "--new-entities"),
                assertYahcliScenarios(s -> {
                    final var contract = s.getContract();
                    assertNotNull(contract);
                    final var persistentContract = contract.getPersistent();
                    assertNotNull(persistentContract);
                    persistentContractNum = persistentContract.getNum();
                    assertEquals(
                            persistentContractNum, persistentContract.getNum(), "Persistent contract should be reused");
                }),
                sourcingContextual(spec -> blockingOrder(
                        getContractInfo(asContractString(
                                spec.contractIdFactory().apply(requireNonNull(persistentContractNum)))),
                        getContractInfo(asContractString(
                                spec.contractIdFactory().apply(requireNonNull(persistentContractNum) + 1L))))));
    }
}
