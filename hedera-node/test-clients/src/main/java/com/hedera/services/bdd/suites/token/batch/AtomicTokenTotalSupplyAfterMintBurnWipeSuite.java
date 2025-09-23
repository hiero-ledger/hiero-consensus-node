// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.batch;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of TokenTotalSupplyAfterMintBurnWipeSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(TOKEN)
@Tag(MATS)
public class AtomicTokenTotalSupplyAfterMintBurnWipeSuite {

    private static String TOKEN_TREASURY = "treasury";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> checkTokenTotalSupplyAfterMintAndBurn() {
        String tokenName = "tokenToTest";
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                cryptoCreate("tokenReceiver").balance(0L),
                newKeyNamed("adminKey"),
                newKeyNamed("supplyKey"),
                tokenCreate(tokenName)
                        .treasury(TOKEN_TREASURY)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(1000)
                        .decimals(1)
                        .supplyKey("supplyKey")
                        .via("createTxn"),
                getTxnRecord("createTxn").logged(),
                atomicBatch(mintToken(tokenName, 1000).via("mintToken").batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord("mintToken").logged(),
                getTokenInfo(tokenName).hasTreasury(TOKEN_TREASURY).hasTotalSupply(2000),
                atomicBatch(burnToken(tokenName, 200).via("burnToken").batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord("burnToken").logged(),
                getTokenInfo(tokenName).logged().hasTreasury(TOKEN_TREASURY).hasTotalSupply(1800));
    }

    @HapiTest
    final Stream<DynamicTest> totalSupplyAfterWipe() {
        var tokenToWipe = "tokenToWipe";

        return defaultHapiSpec("totalSupplyAfterWipe")
                .given(
                        newKeyNamed("wipeKey"),
                        cryptoCreate("assoc1").balance(0L),
                        cryptoCreate("assoc2").balance(0L),
                        cryptoCreate(TOKEN_TREASURY).balance(0L))
                .when(
                        tokenCreate(tokenToWipe)
                                .name(tokenToWipe)
                                .treasury(TOKEN_TREASURY)
                                .initialSupply(1_000)
                                .wipeKey("wipeKey"),
                        tokenAssociate("assoc1", tokenToWipe),
                        tokenAssociate("assoc2", tokenToWipe),
                        cryptoTransfer(moving(500, tokenToWipe).between(TOKEN_TREASURY, "assoc1")),
                        cryptoTransfer(moving(200, tokenToWipe).between(TOKEN_TREASURY, "assoc2")),
                        getAccountBalance("assoc1").hasTokenBalance(tokenToWipe, 500),
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(tokenToWipe, 300),
                        getAccountInfo("assoc1").logged(),
                        atomicBatch(
                                        wipeTokenAccount(tokenToWipe, "assoc1", 200)
                                                .via("wipeTxn1")
                                                .logged()
                                                .batchKey(BATCH_OPERATOR),
                                        wipeTokenAccount(tokenToWipe, "assoc2", 200)
                                                .via("wipeTxn2")
                                                .logged()
                                                .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))
                .then(
                        getAccountBalance("assoc2").hasTokenBalance(tokenToWipe, 0),
                        getTokenInfo(tokenToWipe)
                                .hasTotalSupply(600)
                                .hasName(tokenToWipe)
                                .logged(),
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(tokenToWipe, 300));
    }
}
