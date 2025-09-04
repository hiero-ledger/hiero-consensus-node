// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.scenarios;

import static com.hedera.services.bdd.spec.HapiPropertySource.asFileString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
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
 * Validates that {@code yahcli ivy scenarios --file} creates a persistent file when there is no config* specifying well-known
 * entities; and then reuses that file (but creates a novel file) when run again with the {@code --new-entities} flag.
 */
@Tag(REGRESSION)
@HapiTestLifecycle
@OrderedInIsolation
@TestInstance(PER_CLASS)
public class FileScenarioTest {
    @Nullable
    private Long persistentFileNum;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        // Ensure we start with no config
        lifecycle.doAdhoc(deleteYahcliScenariosConfig());
    }

    @HapiTest
    @Order(0)
    final Stream<DynamicTest> fileScenarioCreatesLongLivedEntitiesWhenNoConfigYet() {
        return hapiTest(
                yahcliIvy("scenarios", "--file"),
                assertYahcliScenarios(s -> {
                    final var file = s.getFile();
                    assertNotNull(file);
                    final var persistentFile = file.getPersistent();
                    assertNotNull(persistentFile);
                    persistentFileNum = persistentFile.getNum();
                    assertNotNull(persistentFileNum, "Persistent file should be auto-created");
                }),
                sourcingContextual(spec -> blockingOrder(
                        getFileInfo(asFileString(spec.fileIdFactory().apply(requireNonNull(persistentFileNum)))))));
    }

    @HapiTest
    @Order(1)
    final Stream<DynamicTest> fileScenarioCreatesJustNovelEntitiesWithConfig() {
        return hapiTest(
                yahcliIvy("scenarios", "--file", "--new-entities"),
                assertYahcliScenarios(s -> {
                    final var file = s.getFile();
                    assertNotNull(file);
                    final var persistentFile = file.getPersistent();
                    assertNotNull(persistentFile);
                    assertEquals(persistentFileNum, persistentFile.getNum(), "Receiver should be reused");
                }),
                sourcingContextual(spec -> blockingOrder(
                        getFileInfo(asFileString(spec.fileIdFactory().apply(requireNonNull(persistentFileNum) + 1L)))
                                .hasDeleted(true))));
    }
}
