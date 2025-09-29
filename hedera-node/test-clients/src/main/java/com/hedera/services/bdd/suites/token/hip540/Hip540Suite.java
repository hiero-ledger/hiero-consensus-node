// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.hip540;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.namedHapiTest;
import static com.hedera.services.bdd.suites.token.hip540.Hip540TestScenarios.ALL_HIP_540_SCENARIOS;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TOKEN)
@Tag(MATS)
public class Hip540Suite {
    @HapiTest
    public final Stream<DynamicTest> allScenariosAsExpected() {
        return ALL_HIP_540_SCENARIOS.stream()
                .map(scenario -> namedHapiTest(scenario.testName(), scenario.asOperation()));
    }
}
