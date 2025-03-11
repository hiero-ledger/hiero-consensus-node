// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.meta;

import static com.hedera.services.bdd.spec.HapiSpec.customHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;

import com.hedera.services.bdd.suites.HapiSuite;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class VersionInfoSpec extends HapiSuite {
    private static final Logger log = LogManager.getLogger(VersionInfoSpec.class);
    private final Map<String, String> specConfig;

    public VersionInfoSpec(final Map<String, String> specConfig) {
        this.specConfig = specConfig;
    }

    public VersionInfoSpec() {
        specConfig = null;
    }

    public static void main(String... args) {
        new VersionInfoSpec().runSuiteSync();
    }

    @Override
    public boolean canRunConcurrent() {
        return true;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(discoversExpectedVersions());
    }

    final Stream<DynamicTest> discoversExpectedVersions() {
        if (specConfig != null) {
            return customHapiSpec("getVersionInfo")
                    .withProperties(specConfig)
                    .given()
                    .when()
                    .then(getVersionInfo().withYahcliLogging().noLogging());
        } else {
            return defaultHapiSpec("discoversExpectedVersions")
                    .given()
                    .when()
                    .then(getVersionInfo().logged().hasNoDegenerateSemvers());
        }
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
