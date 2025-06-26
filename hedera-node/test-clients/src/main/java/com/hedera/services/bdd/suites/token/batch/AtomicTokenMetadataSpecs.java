// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.batch;

import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.salted;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.METADATA_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of TokenMetadataSpecs. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.

/**
 * Validates the {@code TokenCreate} and {@code TokenUpdate} transactions, specifically its:
 * <ul>
 *     <li>Metadata and MetadataKey values and behaviours.</li>
 * </ul>
 */
@HapiTestLifecycle
@Tag(TOKEN)
public class AtomicTokenMetadataSpecs {

    private static final String PRIMARY = "primary";
    private static final String ADMIN_KEY = "adminKey";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String PAYER = "payer";
    private static final String METADATA_KEY = "metadataKey";
    private static final String TOKEN_TREASURY = "treasury";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> rejectsMetadataTooLong() {
        String metadataStringTooLong = TxnUtils.nAscii(101);
        return defaultHapiSpec("validatesMetadataLength")
                .given()
                .when()
                .then(atomicBatch(tokenCreate(PRIMARY)
                                .metaData(metadataStringTooLong)
                                .hasPrecheck(METADATA_TOO_LONG)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> creationDoesNotHaveRequiredSigs() {
        return defaultHapiSpec("CreationRequiresAppropriateSigs")
                .given(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY))
                .when()
                .then(
                        atomicBatch(tokenCreate("shouldntWork")
                                        .treasury(TOKEN_TREASURY)
                                        .payingWith(PAYER)
                                        .adminKey(ADMIN_KEY)
                                        .signedBy(PAYER)
                                        .hasKnownStatus(INVALID_SIGNATURE)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        /* treasury must sign */
                        atomicBatch(tokenCreate("shouldntWorkEither")
                                        .treasury(TOKEN_TREASURY)
                                        .payingWith(PAYER)
                                        .adminKey(ADMIN_KEY)
                                        .signedBy(PAYER, ADMIN_KEY)
                                        .hasKnownStatus(INVALID_SIGNATURE)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> creationRequiresAppropriateSigsHappyPath() {
        return hapiTest(
                cryptoCreate(PAYER),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                newKeyNamed(ADMIN_KEY),
                atomicBatch(tokenCreate("shouldWork")
                                .treasury(TOKEN_TREASURY)
                                .payingWith(PAYER)
                                .adminKey(ADMIN_KEY)
                                .signedBy(TOKEN_TREASURY, PAYER, ADMIN_KEY)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUpdateMetadataWithoutAdminOrMetadataKeySignature() {
        String memo = "JUMP";
        String metadata = "metadata";
        String saltedName = salted(PRIMARY);

        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(100L),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(METADATA_KEY),
                tokenCreate(PRIMARY)
                        .supplyType(TokenSupplyType.FINITE)
                        .entityMemo(memo)
                        .name(saltedName)
                        .treasury(TOKEN_TREASURY)
                        .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                        .adminKey(ADMIN_KEY)
                        .maxSupply(1000)
                        .initialSupply(500)
                        .decimals(1)
                        .metaData(metadata),
                getTokenInfo(PRIMARY).logged(),
                atomicBatch(tokenUpdate(PRIMARY)
                                .newMetadata("newMetadata")
                                .signedBy(DEFAULT_PAYER)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUpdateMetadataOnImmutableToken() {
        String memo = "JUMP";
        String metadata = "metadata";
        String saltedName = salted(PRIMARY);

        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(100L),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(METADATA_KEY),
                tokenCreate(PRIMARY)
                        .supplyType(TokenSupplyType.FINITE)
                        .entityMemo(memo)
                        .name(saltedName)
                        .treasury(TOKEN_TREASURY)
                        .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                        .maxSupply(1000)
                        .initialSupply(500)
                        .decimals(1)
                        .metaData(metadata),
                getTokenInfo(PRIMARY).logged(),
                atomicBatch(tokenUpdate(PRIMARY)
                                .newMetadata("newMetadata")
                                .signedBy(DEFAULT_PAYER)
                                .hasKnownStatus(TOKEN_IS_IMMUTABLE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }
}
