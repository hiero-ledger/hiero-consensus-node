// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;

class AtomicBatchByTxnTypeTest {

    @HapiTest
    @DisplayName("Batch with CryptoTransfer txn type")
    final Stream<DynamicTest> cryptoTransferTest() {
        // see AtomicBatchTest#simpleBatchTest
        return hapiTest();
    }

    @Nested
    class CryptoUpdateBatchTests {
        private static final String key1 = "key1";
        private static final String key2 = "key2";
        private static final String key3 = "key3";
        private static final String key4 = "key4";
        private static final String operatorKey = "operatorKey";
        private static final String operatorAcct = "operatorAcct";
        private static final String acct1 = "acct1";
        private static final String acct2 = "acct2";
        private static final String acct3 = "acct3";

        @BeforeAll
        static void setup() {
            var spec = new HapiSpec("CU batch tests keys", new SpecOperation[] {
                newKeyNamed(key1),
                newKeyNamed(key2),
                newKeyNamed(key3),
                newKeyNamed(key4),
                newKeyNamed(operatorKey).shape(SigControl.ED25519_ON),
                cryptoCreate(operatorAcct)
                        .key(operatorKey)
                        .balance(ONE_MILLION_HBARS)
                        .maxAutomaticTokenAssociations(-1)
                        .signedBy(operatorKey)
                        .hasKnownStatus(SUCCESS),
                cryptoCreate(acct1).key(key1).balance(ONE_HUNDRED_HBARS).signedBy(key1),
                cryptoCreate(acct2).key(key2).balance(ONE_HUNDRED_HBARS).signedBy(key2),
                cryptoCreate(acct3).key(key3).balance(ONE_HUNDRED_HBARS).signedBy(key3)
            });
            allRunFor(spec);
        }

        // tests that change keys
        @HapiTest
        @DisplayName("Batch with CryptoUpdate admin key txn type")
        final Stream<DynamicTest> adminKeyChangeTest() {
            final var innerTxn = cryptoUpdate(acct1).key(key2);

            return hapiTest(
                    cryptoCreate("withAdminKey")
                            .key(key1)
                            .balance(100L)
                            .batchKey(operatorKey)
                            .maxAutomaticTokenAssociations(-1)
                            .payingWith(operatorKey)
                            .signedBy(operatorKey, key1),

                    // verify the batch won't execute without the right sigs
                    atomicBatch(innerTxn)
                            .payingWith("withAdminKey")
                            .signedBy(operatorKey, key1)
                            .hasKnownStatus(INVALID_SIGNATURE),
                    atomicBatch(innerTxn)
                            .payingWith("withAdminKey")
                            .signedBy(operatorKey, key2)
                            .hasKnownStatus(INVALID_SIGNATURE),
                    atomicBatch(innerTxn)
                            .payingWith("withAdminKey")
                            .signedBy(key1, key2)
                            .hasKnownStatus(INVALID_SIGNATURE),
                    atomicBatch(innerTxn)
                            .payingWith("withAdminKey")
                            .signedBy(operatorKey, key1, key2)
                            .hasKnownStatus(SUCCESS),
                    // verify the key change
                    cryptoTransfer(TokenMovement.movingHbar(1L).between("withAdminKey", "acct1"))
                            .payingWith("withAdminKey")
                            .signedBy(operatorKey)
                            .hasKnownStatus(INVALID_SIGNATURE),
                    cryptoTransfer(TokenMovement.movingHbar(1L).between("withAdminKey", "acct1"))
                            .payingWith("withAdminKey")
                            .signedBy(key1)
                            .hasKnownStatus(INVALID_SIGNATURE),
                    cryptoTransfer(TokenMovement.movingHbar(1L).between("withAdminKey", "acct1"))
                            .payingWith("withAdminKey")
                            .signedBy(key2)
                            .hasKnownStatus(SUCCESS));
        }

        @HapiTest
        @DisplayName("")
        final Stream<DynamicTest> batchKeyChangeTest() {
            return hapiTest();
        }

        @HapiTest
        @DisplayName("")
        final Stream<DynamicTest> contractKeyChangeTest() {
            return hapiTest();
        }

        @HapiTest
        @DisplayName("Batch with CryptoUpdate inside contract")
        final Stream<DynamicTest> cryptoUpdateInContract() {
            return hapiTest();
        }
    }
}
