// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.batch;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.restoreDefault;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.APP_PROPERTIES;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_NFTS_IN_PRICE_REGIME_HAVE_BEEN_MINTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of TokenManagementSpecsStateful. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(TOKEN)
@Tag(MATS)
class AtomicTokenManagementSpecsStateful {

    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    public static final String INVALID_ACCOUNT = "999.999.999";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    private HapiSpecOperation[] freezeMgmtFailureCasesWorkBase() {
        var unfreezableToken = "without";
        var freezableToken = "withPlusDefaultTrue";
        return new HapiSpecOperation[] {
            fileUpdate(APP_PROPERTIES)
                    .payingWith(ADDRESS_BOOK_CONTROL)
                    .overridingProps(Map.of("tokens.maxPerAccount", "" + 1000)),
            newKeyNamed("oneFreeze"),
            cryptoCreate(TOKEN_TREASURY).balance(0L),
            cryptoCreate("go").balance(0L),
            tokenCreate(unfreezableToken).treasury(TOKEN_TREASURY),
            tokenCreate(freezableToken)
                    .freezeDefault(true)
                    .freezeKey("oneFreeze")
                    .treasury(TOKEN_TREASURY)
        };
    }

    @HapiTest
    final Stream<DynamicTest> freezeMgmtFailureCasesWork() {
        var unfreezableToken = "without";
        var freezableToken = "withPlusDefaultTrue";

        return hapiTest(flattened(
                freezeMgmtFailureCasesWorkBase(),
                atomicBatch(tokenFreeze(unfreezableToken, TOKEN_TREASURY)
                                .signedBy(GENESIS)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(TOKEN_HAS_NO_FREEZE_KEY))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenFreeze(freezableToken, INVALID_ACCOUNT)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_ACCOUNT_ID))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenFreeze(freezableToken, TOKEN_TREASURY)
                                .signedBy(GENESIS)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenFreeze(freezableToken, "go")
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenUnfreeze(freezableToken, "go")
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenUnfreeze(unfreezableToken, TOKEN_TREASURY)
                                .signedBy(GENESIS)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(TOKEN_HAS_NO_FREEZE_KEY))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenUnfreeze(freezableToken, INVALID_ACCOUNT)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_ACCOUNT_ID))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenUnfreeze(freezableToken, TOKEN_TREASURY)
                                .signedBy(GENESIS)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @LeakyHapiTest(overrides = {"tokens.nfts.maxAllowedMints"})
    final Stream<DynamicTest> nftMintingCapIsEnforced() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                tokenCreate(FUNGIBLE_TOKEN)
                        .initialSupply(0)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .supplyKey("supplyKey"),
                mintToken(FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("Why not?"))),
                overriding("tokens.nfts.maxAllowedMints", "1"),
                atomicBatch(mintToken(FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("Again, why not?")))
                                .hasKnownStatus(MAX_NFTS_IN_PRICE_REGIME_HAVE_BEEN_MINTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                restoreDefault("tokens.nfts.maxAllowedMints"),
                atomicBatch(mintToken(FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("Again, why not?")))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }
}
