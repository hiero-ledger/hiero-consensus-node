// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file.batch;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_FILE_SIZE_EXCEEDED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

// This test cases are direct copies of FileAppendSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
public class AtomicFileAppendSuite {

    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @HapiTest
    final Stream<DynamicTest> appendIdVariantsTreatedAsExpected() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                fileCreate("file").contents("ABC"),
                atomicBatch(fileAppend("file").content("DEF").batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @LeakyHapiTest(overrides = {"files.maxSizeKb"})
    final Stream<DynamicTest> handleRejectsOversized() {
        byte[] BYTES_3K_MINUS1 = new byte[3 * 1024 - 1];
        Arrays.fill(BYTES_3K_MINUS1, (byte) 0xAB);
        byte[] BYTES_1 = new byte[] {(byte) 0xAB};

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                overriding("files.maxSizeKb", "3"),
                fileCreate("file").contents(BYTES_3K_MINUS1),
                fileAppend("file").content(BYTES_1),
                atomicBatch(fileAppend("file")
                                .content(BYTES_1)
                                .hasKnownStatus(MAX_FILE_SIZE_EXCEEDED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }
}
