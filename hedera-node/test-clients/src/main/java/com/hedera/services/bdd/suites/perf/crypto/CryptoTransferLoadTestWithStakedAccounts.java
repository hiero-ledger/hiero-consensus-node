// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.perf.crypto;

import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.*;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.utilops.LoadTest;
import com.hedera.services.bdd.suites.perf.PerfTestLoadSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class CryptoTransferLoadTestWithStakedAccounts extends LoadTest {
    private static final Logger log = LogManager.getLogger(CryptoTransferLoadTestWithStakedAccounts.class);

    @SuppressWarnings("java:S2245") // using java.util.Random in tests is fine
    private final Random r = new Random(38582L);

    private static final long TEST_ACCOUNT_STARTS_FROM = 1001L;
    private static final int STAKED_CREATIONS = 0;

    public static void main(String... args) {
        parseArgs(args);

        CryptoTransferLoadTestWithStakedAccounts suite = new CryptoTransferLoadTestWithStakedAccounts();
        suite.runSuiteSync();
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(runCryptoTransfers());
    }

    final Stream<DynamicTest> runCryptoTransfers() {
        PerfTestLoadSettings settings = new PerfTestLoadSettings();

        Supplier<HapiSpecOperation[]> transferBurst = () -> {
            String sender = "sender";
            String receiver = "receiver";
            if (settings.getTotalAccounts() > 2) {
                int s = r.nextInt(settings.getTotalAccounts());
                int re = 0;
                do {
                    re = r.nextInt(settings.getTotalAccounts());
                } while (re == s);
                sender = String.format("%d", TEST_ACCOUNT_STARTS_FROM + s);
                receiver = String.format("%d", TEST_ACCOUNT_STARTS_FROM + re);
            }

            return new HapiSpecOperation[] {
                cryptoTransfer(tinyBarsFromTo(sender, receiver, 1L))
                        .noLogging()
                        .payingWith(sender)
                        .signedBy(GENESIS)
                        .fee(100_000_000L)
                        .hasKnownStatusFrom(
                                SUCCESS,
                                OK,
                                INSUFFICIENT_PAYER_BALANCE,
                                UNKNOWN,
                                TRANSACTION_EXPIRED,
                                INSUFFICIENT_ACCOUNT_BALANCE)
                        .hasRetryPrecheckFrom(BUSY, PLATFORM_TRANSACTION_NOT_CREATED)
                        .deferStatusResolution()
            };
        };

        return defaultHapiSpec("RunCryptoTransfers-StakedAccount")
                .given(
                        withOpContext(
                                (spec, ignore) -> settings.setFrom(spec.setup().ciPropertiesMap())),
                        logIt(ignore -> settings.toString()))
                .when(
                        cryptoCreate("sender")
                                .balance(ignore -> settings.getInitialBalance())
                                .payingWith(GENESIS)
                                .withRecharging()
                                .key(GENESIS)
                                .rechargeWindow(3)
                                .stakedNodeId(settings.getNodeToStake())
                                .logging()
                                .hasRetryPrecheckFrom(BUSY, DUPLICATE_TRANSACTION, PLATFORM_TRANSACTION_NOT_CREATED),
                        cryptoCreate("receiver")
                                .payingWith(GENESIS)
                                .stakedNodeId(settings.getNodeToStake())
                                .hasRetryPrecheckFrom(BUSY, DUPLICATE_TRANSACTION, PLATFORM_TRANSACTION_NOT_CREATED)
                                .key(GENESIS)
                                .logging(),
                        withOpContext((spec, opLog) -> {
                            List<SpecOperation> ops = new ArrayList<>();
                            var stakedNodeId = settings.getNodeToStake();
                            for (int i = 0; i < STAKED_CREATIONS; i++) {
                                var stakedAccount = "stakedAccount" + i;
                                if (settings.getExtraNodesToStake().length != 0) {
                                    stakedNodeId =
                                            settings.getExtraNodesToStake()[i % settings.getExtraNodesToStake().length];
                                }
                                ops.add(cryptoCreate(stakedAccount)
                                        .payingWith("sender")
                                        .stakedNodeId(stakedNodeId)
                                        .fee(ONE_HBAR)
                                        .deferStatusResolution()
                                        .signedBy("sender", DEFAULT_PAYER));
                            }
                            allRunFor(spec, ops);
                        }))
                .then(
                        defaultLoadTest(transferBurst, settings),
                        getAccountBalance("sender").payingWith(GENESIS).logged());
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
