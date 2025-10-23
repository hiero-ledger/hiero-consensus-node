// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;

import com.hedera.services.bdd.junit.HapiTest;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

// This test cases are direct copies of FileAppendSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
class AtomicFileCreateSuite {

    private static final String BATCH_OPERATOR = "batchOperator";

    @HapiTest
    final Stream<DynamicTest> exchangeRateControlAccountIsntCharged() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, 1_000_000_000_000L)),
                balanceSnapshot("pre", EXCHANGE_RATE_CONTROL),
                getFileContents(EXCHANGE_RATES).saveTo("exchangeRates.bin"),
                atomicBatch(fileUpdate(EXCHANGE_RATES)
                                .payingWith(EXCHANGE_RATE_CONTROL)
                                .path(Path.of("./", "exchangeRates.bin").toString())
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountBalance(EXCHANGE_RATE_CONTROL).hasTinyBars(changeFromSnapshot("pre", 0)));
    }
}
