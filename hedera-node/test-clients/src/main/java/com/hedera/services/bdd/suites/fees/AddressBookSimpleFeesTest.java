// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;

import com.hedera.services.bdd.junit.HapiTest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(MATS)
@Tag(SIMPLE_FEES)
public class AddressBookSimpleFeesTest {
    // node create is only allowed by fee exempt payers
    private static final double NODE_UPDATE_BASE_FEE = 0.001;
    private static final double NODE_DELETE_BASE_FEE = 0.001;

    private static List<X509Certificate> gossipCertificates;

    @BeforeAll
    static void beforeAll() {
        gossipCertificates = generateX509Certificates(2);
    }

    @HapiTest
    final Stream<DynamicTest> nodeOperationsBaseFees() throws CertificateEncodingException {
        return hapiTest(
                cryptoCreate("payer").balance(ONE_HBAR),
                cryptoCreate("testNodeAccount"),
                nodeCreate("testNode", "testNodeAccount")
                        .fee(ONE_HBAR)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .memo("testMemo")
                        .adminKey("payer")
                        // we have to pay with privileged account
                        .payingWith(GENESIS)
                        .signedBy("payer", GENESIS),
                nodeUpdate("testNode")
                        .payingWith("payer")
                        .signedBy("payer")
                        .description("newDesc")
                        .via("nodeUpdateTxn"),
                nodeDelete("testNode").payingWith("payer").signedBy("payer").via("nodeDeleteTxn"),
                validateChargedUsd("nodeUpdateTxn", NODE_UPDATE_BASE_FEE),
                validateChargedUsd("nodeDeleteTxn", NODE_DELETE_BASE_FEE));
    }
}
