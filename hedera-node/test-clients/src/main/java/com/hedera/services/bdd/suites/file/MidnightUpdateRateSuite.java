// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.suites.HapiSuite.ADEQUATE_FUNDS;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.transactions.file.HapiFileUpdate;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import java.text.ParseException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

// The test in this suite was extracted from ExchangeRateControlSuite. It has to be run shortly
// before midnight, which is not practical. Either we add functionality to mock time in e2e-test
// or we move this test to the xtests.
//
// https://github.com/hashgraph/hedera-services/issues/8950
public class MidnightUpdateRateSuite {
    final HapiFileUpdate resetRatesOp = fileUpdate(EXCHANGE_RATES)
            .payingWith(EXCHANGE_RATE_CONTROL)
            .fee(ADEQUATE_FUNDS)
            .contents(spec -> spec.ratesProvider().rateSetWith(1, 12).toByteString());

    final Stream<DynamicTest> acct57UpdatesMidnightRateAtMidNight() throws ParseException {
        return hapiTest(
                resetRatesOp,
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, ADEQUATE_FUNDS)),
                // should be done just before midnight
                UtilVerbs.waitUntil("23:58:00"),
                fileUpdate(EXCHANGE_RATES)
                        .contents(spec -> {
                            ByteString newRates =
                                    spec.ratesProvider().rateSetWith(10, 147).toByteString();
                            spec.registry().saveBytes("midnightRate", newRates);
                            return newRates;
                        })
                        .payingWith(EXCHANGE_RATE_CONTROL),
                // should be the first transaction after midnight
                UtilVerbs.sleepFor(300_000),
                fileUpdate(EXCHANGE_RATES)
                        .contents(spec -> {
                            ByteString newRates =
                                    spec.ratesProvider().rateSetWith(10, 183).toByteString();
                            spec.registry().saveBytes("newRates", newRates);
                            return newRates;
                        })
                        .payingWith(EXCHANGE_RATE_CONTROL),
                getFileContents(EXCHANGE_RATES)
                        .hasContents(spec -> spec.registry().getBytes("newRates")),
                resetRatesOp);
    }
}
