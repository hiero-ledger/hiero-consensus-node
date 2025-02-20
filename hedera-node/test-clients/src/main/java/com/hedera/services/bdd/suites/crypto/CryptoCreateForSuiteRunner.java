// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.spec.HapiSpec.customHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.NOISY_RETRY_PRECHECKS;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.spec.utilops.LoadTest;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.bdd.suites.SuiteRunner;
import com.hederahashgraph.api.proto.java.AccountID;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;

/**
 * When running JRS regression tests using SuiteRunner we need to create unique payer accounts for
 * each test client. This class should be used only for that purpose and not be used in any other
 * testing.
 */
public class CryptoCreateForSuiteRunner extends HapiSuite {
    private static final Logger log = LogManager.getLogger(CryptoCreateForSuiteRunner.class);
    private final String nodes;
    private final String defaultNode;

    // Use more initialBalance for this account as it is used as payer for the performance tests
    private static final long INITIAL_BALANCE = 5L * LoadTest.initialBalance.getAsLong();

    public CryptoCreateForSuiteRunner(String nodes, String defaultNode) {
        this.nodes = nodes;
        this.defaultNode = defaultNode;
    }

    public static void main(String... args) {
        new CryptoCreateForSuiteRunner("localhost", "3").runSuiteSync();
    }

    @Override
    public boolean getDeferResultsSummary() {
        return true;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(createAccount());
    }

    @SuppressWarnings({"java:S5960", "java:S1141", "java:S1135"})
    final Stream<DynamicTest> createAccount() {
        int maxRetries = 5;
        return customHapiSpec("CreatePayerAccountForEachClient")
                .withProperties(Map.of("nodes", nodes, "default.node", defaultNode))
                .given()
                .when()
                .then(withOpContext((spec, log) -> {
                    var createdAuditablePayer = false;
                    var retryCount = 0;
                    while (!createdAuditablePayer && retryCount < maxRetries) {
                        try {
                            AccountID id = spec.registry().getAccountID(DEFAULT_PAYER);
                            var cryptoCreateOp = cryptoCreate("payerAccount")
                                    .balance(id.getAccountNum() == 2L ? INITIAL_BALANCE : THOUSAND_HBAR)
                                    .withRecharging()
                                    .rechargeWindow(3)
                                    .key(DEFAULT_PAYER)
                                    .payingWith(GENESIS)
                                    .hasRetryPrecheckFrom(NOISY_RETRY_PRECHECKS)
                                    .via("txn")
                                    .ensuringResolvedStatusIsntFromDuplicate();
                            allRunFor(spec, cryptoCreateOp);
                            var gotCreationRecord = false;
                            while (!gotCreationRecord) {
                                try {
                                    var getRecordOp = getTxnRecord("txn")
                                            .assertingNothing()
                                            .saveTxnRecordToRegistry("savedTxnRcd")
                                            .logged();
                                    allRunFor(spec, getRecordOp);
                                    gotCreationRecord = true;
                                } catch (Exception ignoreAndRetry) {
                                    // Intentionally ignored
                                }
                            }
                            createdAuditablePayer = true;
                        } catch (Exception ignoreAgainAndRetry) {
                            retryCount++;
                        }
                    }
                    var status = spec.registry()
                            .getTransactionRecord("savedTxnRcd")
                            .getReceipt()
                            .getStatus();
                    Assertions.assertEquals(SUCCESS, status, "Failed to create payer account!");

                    var gotPayerInfo = false;
                    while (!gotPayerInfo) {
                        try {
                            var payerAccountInfo = getAccountInfo("payerAccount")
                                    .savingSnapshot("payerAccountInfo")
                                    .payingWith(GENESIS)
                                    .logged();
                            allRunFor(spec, payerAccountInfo);
                            gotPayerInfo = true;
                        } catch (Exception ignoreAndRetry) {
                            // Intentionally ignored
                        }
                    }

                    // TODO Should be modified in a different way to avoid setting a
                    // static variable of
                    // other class
                    SuiteRunner.setPayerId(String.valueOf(spec.registry()
                            .getAccountInfo("payerAccountInfo")
                            .getAccountID()
                            .getAccountNum()));
                }));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
