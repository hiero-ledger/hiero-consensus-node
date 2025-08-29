// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.suites.HapiSuite;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class BalanceSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(BalanceSuite.class);

    private final Map<String, String> specConfig;
    private final List<String> accounts;

    private final AtomicReference<Map<String, Long>> balances = new AtomicReference<>(new HashMap<>());

    public BalanceSuite(final Map<String, String> specConfig, final String[] accounts) {
        this.specConfig = specConfig;
        this.accounts = rationalized(accounts);
    }

    public Map<String, Long> balancesByRepr() {
        return balances.get();
    }

    private List<String> rationalized(final String[] accounts) {
        return Arrays.stream(accounts).map(Utils::extractAccount).collect(Collectors.toList());
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        List<Stream<DynamicTest>> specToRun = new ArrayList<>();
        accounts.forEach(s -> specToRun.add(getBalance(s)));
        return specToRun;
    }

    final Stream<DynamicTest> getBalance(String accountID) {
        return HapiSpec.customHapiSpec("getBalance")
                .withProperties(specConfig)
                .given()
                .when()
                .then(getAccountBalance(accountID).noLogging().exposingBalanceTo(value -> balancesByRepr()
                        .put(accountID, value)));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
