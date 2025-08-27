// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.batch;

import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of TokenDeleteSpecs. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(TOKEN)
public class AtomicTokenDeleteSpecs {

    private static final String FIRST_TBD = "firstTbd";
    private static final String SECOND_TBD = "secondTbd";
    private static final String TOKEN_ADMIN = "tokenAdmin";
    private static final String PAYER = "payer";
    private static final String MULTI_KEY = "multiKey";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> treasuryBecomesDeletableAfterTokenDelete() {
        return hapiTest(
                newKeyNamed(TOKEN_ADMIN),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                tokenCreate(FIRST_TBD).adminKey(TOKEN_ADMIN).treasury(TOKEN_TREASURY),
                tokenCreate(SECOND_TBD).adminKey(TOKEN_ADMIN).treasury(TOKEN_TREASURY),
                cryptoDelete(TOKEN_TREASURY).hasKnownStatus(ACCOUNT_IS_TREASURY),
                tokenDissociate(TOKEN_TREASURY, FIRST_TBD).hasKnownStatus(ACCOUNT_IS_TREASURY),
                atomicBatch(
                                tokenDelete(FIRST_TBD).batchKey(BATCH_OPERATOR),
                                tokenDissociate(TOKEN_TREASURY, FIRST_TBD).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(cryptoDelete(TOKEN_TREASURY)
                                .hasKnownStatus(ACCOUNT_IS_TREASURY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                tokenDelete(SECOND_TBD),
                tokenDissociate(TOKEN_TREASURY, SECOND_TBD),
                cryptoDelete(TOKEN_TREASURY));
    }

    @HapiTest
    final Stream<DynamicTest> deletionValidatesAlreadyDeletedToken() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                tokenCreate("tbd").adminKey(MULTI_KEY).treasury(TOKEN_TREASURY),
                tokenDelete("tbd"),
                atomicBatch(tokenDelete("tbd").hasKnownStatus(TOKEN_WAS_DELETED).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> deletionValidatesMissingAdminKey() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                cryptoCreate(PAYER),
                tokenCreate("tbd").freezeDefault(false).treasury(TOKEN_TREASURY).payingWith(PAYER),
                atomicBatch(tokenDelete("tbd")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .hasKnownStatus(TOKEN_IS_IMMUTABLE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> deletionValidatesRef() {
        return hapiTest(
                cryptoCreate(PAYER),
                atomicBatch(tokenDelete("0.0.0")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .hasKnownStatus(INVALID_TOKEN_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenDelete("1.2.3")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .hasKnownStatus(INVALID_TOKEN_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }
}
