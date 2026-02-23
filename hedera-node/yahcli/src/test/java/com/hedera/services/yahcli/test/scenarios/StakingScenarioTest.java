// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.scenarios;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.deleteYahcliScenariosConfig;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliIvy;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;

@Tag(REGRESSION)
@HapiTestLifecycle
@OrderedInIsolation
@TestInstance(PER_CLASS)
public class StakingScenarioTest {
    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        // Ensure we start with no config to verify this scenario also creates payer if needed
        lifecycle.doAdhoc(deleteYahcliScenariosConfig());
    }

    @HapiTest
    final Stream<DynamicTest> stakingScenarioStakesToAllNodesSuccessfully() {
        return hapiTest(
                // Throws if scenario payer fails to stake to any node account in 0.0.102
                yahcliIvy("scenarios", "--staking"));
    }
}
