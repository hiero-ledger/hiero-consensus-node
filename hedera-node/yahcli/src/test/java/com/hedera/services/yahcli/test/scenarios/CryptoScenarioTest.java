// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.scenarios;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.YahcliVerbs.yahcliIvy;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(REGRESSION)
public class CryptoScenarioTest {
    @HapiTest
    final Stream<DynamicTest> cryptoScenarioCreatesLongLivedEntitiesWhenMissing() {
        return hapiTest(yahcliIvy("vs", "--crypto"));
    }
}
