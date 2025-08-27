package com.hedera.services.yahcli.test.scenarios;

import com.hedera.services.bdd.junit.HapiTest;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

import java.util.stream.Stream;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.YahcliVerbs.yahcliIvy;

@Tag(REGRESSION)
public class CryptoScenarioTest {
    @HapiTest
    final Stream<DynamicTest> cryptoScenarioCreatesLongLivedEntitiesWhenMissing() {
        return hapiTest(yahcliIvy("vs", "--crypto"));
    }
}
