// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.records;

import static com.hedera.services.bdd.spec.HapiSpec.customHapiSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.junit.support.validators.utils.AccountClassifier;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class BalanceValidation extends HapiSuite {
    private static final Logger log = LogManager.getLogger(BalanceValidation.class);

    private final Map<Long, Long> expectedBalances;
    private final AccountClassifier accountClassifier;

    public BalanceValidation(final Map<Long, Long> expectedBalances, final AccountClassifier accountClassifier) {
        this.expectedBalances = expectedBalances;
        this.accountClassifier = accountClassifier;
    }

    public static void main(String... args) {
        // Treasury starts with 50B hbar
        new BalanceValidation(Map.of(2L, 50_000_000_000L * TINY_PARTS_PER_WHOLE), new AccountClassifier())
                .runSuiteSync();
    }

    @Override
    public boolean canRunConcurrent() {
        return true;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(validateBalances());
    }

    final Stream<DynamicTest> validateBalances() {
        return customHapiSpec("ValidateBalances")
                .withProperties(Map.of(
                        "fees.useFixedOffer", "true",
                        "fees.fixedOffer", "100000000"))
                .given()
                .when()
                .then(inParallel(expectedBalances.entrySet().stream()
                                .map(entry -> {
                                    final var accountNum = entry.getKey();
                                    return QueryVerbs.getAccountBalance(
                                                    String.valueOf(accountNum),
                                                    accountClassifier.isContract(accountNum))
                                            .hasAnswerOnlyPrecheckFrom(CONTRACT_DELETED, ACCOUNT_DELETED, OK)
                                            .hasTinyBars(entry.getValue());
                                })
                                .toArray(HapiSpecOperation[]::new))
                        .failOnErrors());
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
