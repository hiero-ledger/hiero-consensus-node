// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.suites.HapiSuite;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class InfoSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(InfoSuite.class);

    private final Map<String, String> specConfig;
    private final List<String> accounts;

    public InfoSuite(final Map<String, String> specConfig, final String[] accounts) {
        this.specConfig = specConfig;
        this.accounts = rationalized(accounts);
    }

    private List<String> rationalized(final String[] accounts) {
        return Arrays.stream(accounts).map(Utils::extractAccount).toList();
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        List<Stream<DynamicTest>> specToRun = new ArrayList<>();
        accounts.forEach(s -> specToRun.add(getInfo(s)));
        return specToRun;
    }

    final Stream<DynamicTest> getInfo(String accountID) {
        return HapiSpec.customHapiSpec("getInfo")
                .withProperties(specConfig)
                .given()
                .when()
                .then(getAccountInfo(accountID).logged().loggingHexedKeys());
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
